package com.csgo.tradeup.proxy;

import com.csgo.tradeup.proxy.controller.xunics.*;
import com.csgo.tradeup.proxy.config.XunicsQueryBindingAdvice;
import com.csgo.tradeup.proxy.service.impl.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;
import com.csgo.tradeup.proxy.integration.xunics.XunicsClient;
import com.csgo.tradeup.proxy.controller.xunics.XunicsProxyExceptionHandler;
import com.csgo.tradeup.proxy.integration.xunics.XunicsHttpConfiguration;
import com.csgo.tradeup.proxy.integration.xunics.XunicsProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.StreamUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实本地 HTTP 上游验证转发，不调用线上接口或进行真实采购。 */
class XunicsProxyTest {
    private HttpServer server;
    private ExecutorService executor;
    private CloseableHttpClient client;
    private XunicsProperties properties;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper().configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String ORDER = "{\"external_order_no\":\"purchase-001\",\"max_price\":\"12.00000000\","
            + "\"trade_url\":\"https://steamcommunity.com/tradeoffer/new/?partner=123&token=abc\"}";
    private static final String QUICK = ORDER.substring(0, ORDER.length() - 1) + ",\"market_hash_name\":\"AK-47 | Redline\"}";
    private final BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        reply.set(new Reply(200, "{\"code\":200,\"msg\":\"ok\",\"data\":null}"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/public/", exchange -> {
            received.incrementAndGet();
            CapturedRequest request = new CapturedRequest();
            request.method = exchange.getRequestMethod();
            request.uri = exchange.getRequestURI().toASCIIString();
            request.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            request.cookie = exchange.getRequestHeaders().getFirst("Cookie");
            request.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            request.body = StreamUtils.copyToByteArray(exchange.getRequestBody());
            requests.add(request);
            Reply response = reply.get();
            try {
                if (response.delayMillis > 0) Thread.sleep(response.delayMillis);
                if (response.disconnect) return;
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                response.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(response.status, response.body.length);
                exchange.getResponseBody().write(response.body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        properties = new XunicsProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/public");
        properties.setApiKey("test-server-key");
        properties.setReadTimeout(Duration.ofSeconds(2));
        configureClient();
    }

    private void configureClient() throws Exception {
        if (client != null) client.close();
        client = new XunicsHttpConfiguration().xunicsHttpClient(properties);
        XunicsClient gateway = new XunicsClient(properties, client, mapper);
        XunicsItemServiceImpl items = new XunicsItemServiceImpl();
        XunicsOrderServiceImpl orders = new XunicsOrderServiceImpl();
        XunicsWalletServiceImpl wallets = new XunicsWalletServiceImpl();
        ReflectionTestUtils.setField(items, "client", gateway);
        ReflectionTestUtils.setField(orders, "client", gateway);
        ReflectionTestUtils.setField(wallets, "client", gateway);
        XunicsItemController itemController = new XunicsItemController();
        XunicsQuoteController quoteController = new XunicsQuoteController();
        XunicsOrderController orderController = new XunicsOrderController();
        XunicsWalletController walletController = new XunicsWalletController();
        ReflectionTestUtils.setField(itemController, "service", items);
        ReflectionTestUtils.setField(quoteController, "service", items);
        ReflectionTestUtils.setField(orderController, "service", orders);
        ReflectionTestUtils.setField(walletController, "service", wallets);
        mvc = MockMvcBuilders.standaloneSetup(itemController, quoteController, orderController, walletController)
                .setControllerAdvice(new XunicsProxyExceptionHandler(), new XunicsQueryBindingAdvice(mapper))
                .build();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void everyDocumentedEndpointHasAnIndependentTypedMethodAndUnifiedResult() throws Exception {
        JsonNode paths = mapper.readTree(Paths.get("docs/xunics-openapi.json").toFile()).get("paths");
        int count = 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> endpoints = paths.fields();
        while (endpoints.hasNext()) {
            Map.Entry<String, JsonNode> endpoint = endpoints.next();
            String path = "/api/public" + endpoint.getKey()
                    .replace("{quote_id}", "q_12345678901234567890123456789012")
                    .replace("{order_no}", "ord_12345678901234567890123456789012");
            java.util.Iterator<String> methods = endpoint.getValue().fieldNames();
            while (methods.hasNext()) {
                String method = methods.next().toUpperCase();
                MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path);
                if ("POST".equals(method)) {
                    String body = path.endsWith("/batch") ? "{\"items\":[" + QUICK + "]}" :
                            path.endsWith("/quick") ? QUICK : ORDER.substring(0, ORDER.length() - 1)
                            + ",\"quote_id\":\"q_12345678901234567890123456789012\"}";
                    request.contentType("application/json").content(body);
                }
                if (path.endsWith("/items")) request.param("market_hash_name", "AK-47 | Redline");
                mvc.perform(request).andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.message").value("成功"))
                        .andExpect(jsonPath("$.msg").doesNotExist());
                CapturedRequest upstream = takeRequest();
                assertEquals(path, URI.create(upstream.uri).getPath());
                assertEquals(method, upstream.method);
                assertEquals("Bearer test-server-key", upstream.authorization);
                count++;
            }
        }
        assertEquals(10, count);
    }

    @Test
    void snakeCaseQueryBindsToDtoWithoutLosingPrecisionOrChineseCharacters() throws Exception {
        mvc.perform(get("/api/public/v1/items")
                        .param("market_hash_name", "AK-47 | 红线 (Field-Tested)")
                        .param("float_min", "0.123456789012345678901234567890")
                        .param("max_price", "12.00000000").param("page", "2").param("size", "50")
                        .header("Authorization", "Bearer caller-key").header("Cookie", "session=caller"))
                .andExpect(status().isOk());
        CapturedRequest upstream = takeRequest();
        Map<String, String> query = new LinkedHashMap<>();
        org.apache.http.client.utils.URLEncodedUtils.parse(URI.create(upstream.uri), "UTF-8")
                .forEach(pair -> query.put(pair.getName(), pair.getValue()));
        assertEquals("AK-47 | 红线 (Field-Tested)", query.get("market_hash_name"));
        assertEquals("0.123456789012345678901234567890", query.get("float_min"));
        assertEquals("12.00000000", query.get("max_price"));
        assertEquals("2", query.get("page"));
        assertFalse(query.containsKey("marketHashName"));
        assertEquals("Bearer test-server-key", upstream.authorization);
        assertNull(upstream.cookie);
    }

    @Test
    void typedPostPreservesBusinessOrderPriceAndRecipient() throws Exception {
        reply.set(new Reply(200, "{\"code\":200,\"data\":{\"order_no\":\"ord_test\",\"max_price\":\"12.00000000\",\"status\":\"queued\"}}"));
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(QUICK))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.order_no").value("ord_test"))
                .andExpect(jsonPath("$.data.max_price").value("12.00000000"));
        JsonNode sent = mapper.readTree(takeRequest().body);
        assertEquals(mapper.readTree(QUICK), sent);
        assertFalse(sent.has("delivery_type"));
    }

    @Test
    void officialPositiveMoneyFormsAndJsonNumbersAreForwardedWithoutLosingValue() throws Exception {
        for (String amount : Arrays.asList(".5", "+1", "1.")) {
            mvc.perform(get("/api/public/v1/items").param("market_hash_name", "AK").param("max_price", amount))
                    .andExpect(status().isOk());
            Map<String, String> query = new LinkedHashMap<>();
            org.apache.http.client.utils.URLEncodedUtils.parse(URI.create(takeRequest().uri), "UTF-8")
                    .forEach(pair -> query.put(pair.getName(), pair.getValue()));
            assertEquals(amount, query.get("max_price"));
            String order = QUICK.replace("\"12.00000000\"", "\"" + amount + "\"");
            mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(order))
                    .andExpect(status().isOk());
            assertEquals(amount, mapper.readTree(takeRequest().body).get("max_price").asText());
        }
        for (String amount : Arrays.asList("12.34567890", "1234567890123456789012.12345678")) {
            String numericOrder = QUICK.replace("\"12.00000000\"", amount);
            mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(numericOrder))
                    .andExpect(status().isOk());
            assertEquals(0, new java.math.BigDecimal(amount)
                    .compareTo(new java.math.BigDecimal(mapper.readTree(takeRequest().body).get("max_price").asText())));
        }

        for (String amount : Arrays.asList("0", "-1", ".", "1e3")) {
            mvc.perform(get("/api/public/v1/items").param("market_hash_name", "AK").param("max_price", amount))
                    .andExpect(status().isBadRequest());
            String order = QUICK.replace("\"12.00000000\"", "\"" + amount + "\"");
            mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(order))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void orderBodiesRejectUnknownFieldsIncludingInsideBatch() throws Exception {
        String unknown = QUICK.substring(0, QUICK.length() - 1) + ",\"delivery_typo\":\"manual\"}";
        String quoted = ORDER.substring(0, ORDER.length() - 1)
                + ",\"quote_id\":\"q_12345678901234567890123456789012\",\"unexpected\":true}";
        mvc.perform(post("/api/public/v1/orders").contentType("application/json").content(quoted))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(unknown))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/public/v1/orders/quick/batch").contentType("application/json")
                        .content("{\"items\":[" + unknown + "]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/public/v1/orders/quick/batch").contentType("application/json")
                        .content("{\"items\":[" + QUICK + "],\"unexpected\":true}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, received.get());
    }

    @Test
    void officialSuccessEnvelopeCanOmitDefaultCode() throws Exception {
        reply.set(new Reply(200, "{\"data\":{\"currency\":\"USD\",\"balance\":\"1.00000000\"}}"));
        mvc.perform(get("/api/public/v1/wallets/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value("1.00000000"));
        takeRequest();
        reply.set(new Reply(200, "{\"code\":null,\"data\":{}}"));
        mvc.perform(get("/api/public/v1/wallets/me")).andExpect(status().isBadGateway());
        takeRequest();
    }

    @Test
    void typedNestedGoodsAndWalletDataRetainOriginalFieldNames() throws Exception {
        reply.set(new Reply(200, "{\"code\":200,\"data\":{\"items\":[{\"quote_id\":\"q_test\",\"price\":\"12.00000000\","
                + "\"item\":{\"market_hash_name\":\"AK\",\"float_value\":\"0.12345678901234567890\","
                + "\"stickers\":[{\"market_hash_name\":\"sticker\",\"slot\":1}]}}],\"total\":1,\"page\":1,\"size\":20,\"total_pages\":1}}"));
        mvc.perform(get("/api/public/v1/items").param("market_hash_name", "AK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].price").value("12.00000000"))
                .andExpect(jsonPath("$.data.items[0].item.float_value").value("0.12345678901234567890"))
                .andExpect(jsonPath("$.data.items[0].item.stickers[0].slot").value(1))
                .andExpect(jsonPath("$.data.total_pages").value(1));
        takeRequest();
        reply.set(new Reply(200, "{\"code\":200,\"data\":{\"currency\":\"USD\",\"balance\":\"100.12345678\",\"available_balance\":\"80.12345678\",\"enabled\":true}}"));
        mvc.perform(get("/api/public/v1/wallets/me"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value("100.12345678"))
                .andExpect(jsonPath("$.data.available_balance").value("80.12345678"));
    }

    @Test
    void upstreamErrorsBecomeUnifiedErrorsWithTraceAndRetryHints() throws Exception {
        for (int code : Arrays.asList(401, 403, 404, 409, 422, 429, 500, 503)) {
            Reply failure = new Reply(code, "{\"code\":" + code + ",\"msg\":\"upstream failure\",\"data\":null,"
                    + "\"error_code\":\"RATE_LIMITED\",\"trace_id\":\"trace-original\","
                    + "\"details\":[{\"field\":\"body.max_price\",\"type\":\"greater_than\",\"msg\":\"invalid\"}]}" );
            failure.headers.put("Retry-After", "17");
            reply.set(failure);
            mvc.perform(get("/api/public/v1/wallets/me"))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.message").value("upstream failure"))
                    .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.data.traceId").value("trace-original"))
                    .andExpect(jsonPath("$.field").value("body.max_price"))
                    .andExpect(jsonPath("$.retryAfter").value(17))
                    .andExpect(header().string("Retry-After", "17"));
            takeRequest();
        }
    }

    @Test
    void invalidParametersFailLocallyUsingUnifiedFormat() throws Exception {
        mvc.perform(get("/api/public/v1/items"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/public/v1/items").param("market_hash_name", "AK").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.details").isArray());
        mvc.perform(post("/api/public/v1/orders/quick/batch").contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertEquals(0, received.get());
    }

    @Test
    void batchResultUsesTypedItemsAndKeepsPartialSuccess() throws Exception {
        reply.set(new Reply(200, "{\"code\":200,\"data\":{\"accepted\":1,\"rejected\":1,\"items\":["
                + "{\"external_order_no\":\"a\",\"accepted\":true,\"order\":{\"order_no\":\"ord_a\"}},"
                + "{\"external_order_no\":\"b\",\"accepted\":false,\"error_code\":\"INSUFFICIENT_BALANCE\",\"message\":\"余额不足\"}]}}"));
        mvc.perform(post("/api/public/v1/orders/quick/batch").contentType("application/json").content("{\"items\":["+QUICK+"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.items[1].error_code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void timeoutAndConnectionFailureDoNotRetryOrders() throws Exception {
        properties.setReadTimeout(Duration.ofMillis(200));
        configureClient();
        Reply slow = new Reply(200, "{}");slow.delayMillis = 1500;reply.set(slow);
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(QUICK))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("PROXY_UPSTREAM_TIMEOUT"));
        assertEquals(1, received.get());
        Reply drop = new Reply(200, "{}");drop.disconnect = true;reply.set(drop);
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content(QUICK))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.errorCode").value("PROXY_UPSTREAM_UNAVAILABLE"));
        assertEquals(2, received.get());
    }

    @Test
    void missingKeyAndInvalidUpstreamPayloadUseUnifiedErrors() throws Exception {
        properties.setApiKey("");
        mvc.perform(get("/api/public/v1/wallets/me")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("PROXY_NOT_CONFIGURED"));
        assertEquals(0, received.get());
        properties.setApiKey("test-server-key");
        reply.set(new Reply(200, "not json"));
        mvc.perform(get("/api/public/v1/wallets/me")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false));
        Reply redirect = new Reply(307, "redirect");
        redirect.headers.put("Location", properties.getBaseUrl()+"/v1/wallets/me");reply.set(redirect);
        mvc.perform(get("/api/public/v1/wallets/me")).andExpect(status().isBadGateway());
        assertEquals(2, received.get());
    }

    @Test
    void responseModelsCoverAllDocumentedProperties() throws Exception {
        JsonNode schemas = mapper.readTree(Paths.get("docs/xunics-openapi.json").toFile()).path("components").path("schemas");
        Map<String, Class<?>> models = new LinkedHashMap<>();
        models.put("GetOrderDetail", OrderInfo.class);models.put("ItemQuote", ItemQuoteInfo.class);
        models.put("ListingDetail", ListingInfo.class);models.put("WalletBalance", WalletBalanceInfo.class);
        models.put("WalletLedgerEntry", WalletLedgerInfo.class);models.put("WalletHoldEntry", WalletHoldInfo.class);
        models.put("StickerDetail", StickerInfo.class);models.put("CharmDetail", CharmInfo.class);
        models.put("QuickOrderResult", BatchOrderItemInfo.class);models.put("QuickOrderBatch", BatchOrderInfo.class);
        for (Map.Entry<String, Class<?>> entry : models.entrySet()) {
            java.util.Set<String> fields = mapper.getDeserializationConfig().introspect(mapper.constructType(entry.getValue()))
                    .findProperties().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toSet());
            schemas.path(entry.getKey()).path("properties").fieldNames().forEachRemaining(name ->
                    assertTrue(fields.contains(name), entry.getKey()+" missing "+name));
        }
    }

    private CapturedRequest takeRequest() throws InterruptedException {
        CapturedRequest request = requests.poll(2, TimeUnit.SECONDS);assertNotNull(request);return request;
    }
    private static class CapturedRequest {
        String method, uri, authorization, cookie, contentType;
        byte[] body;
    }
    private static class Reply {
        final int status;
        byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();
        long delayMillis;
        boolean disconnect;
        Reply(int status, String body) {this.status = status;this.body = body.getBytes(StandardCharsets.UTF_8);}
    }
}
