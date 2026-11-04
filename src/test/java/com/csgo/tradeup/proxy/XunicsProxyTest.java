package com.csgo.tradeup.proxy;

import com.csgo.tradeup.proxy.controller.xunics.XunicsProxyController;
import com.csgo.tradeup.proxy.controller.xunics.XunicsProxyExceptionHandler;
import com.csgo.tradeup.proxy.integration.xunics.XunicsHttpConfiguration;
import com.csgo.tradeup.proxy.integration.xunics.XunicsProperties;
import com.csgo.tradeup.proxy.integration.xunics.XunicsProxyService;
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
        mvc = MockMvcBuilders.standaloneSetup(new XunicsProxyController(new XunicsProxyService(properties, client)))
                .setControllerAdvice(new XunicsProxyExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void everyDocumentedEndpointForwardsTheOriginalRequestAndResponse() throws Exception {
        JsonNode paths = new ObjectMapper().readTree(Paths.get("docs/xunics-openapi.json").toFile()).get("paths");
        String original = "{ \"external_order_no\":\"purchase-001\",\"max_price\":\"12.00000000\","
                + "\"trade_url\":\"https://steamcommunity.com/tradeoffer/new/?partner=123&token=abc\","
                + "\"market_hash_name\":\"饰品\",\"items\":[{\"unknown_field\":12345678901234567890}] }";
        byte[] body = original.getBytes(StandardCharsets.UTF_8);
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
                if ("POST".equals(method)) request.contentType("application/json").content(body);
                mvc.perform(request).andExpect(status().isOk()).andExpect(content().bytes(reply.get().body));
                CapturedRequest upstream = takeRequest();
                assertEquals(path, upstream.uri);
                assertEquals(method, upstream.method);
                assertEquals("Bearer test-server-key", upstream.authorization);
                if ("POST".equals(method)) assertArrayEquals(body, upstream.body);
                count++;
            }
        }
        assertEquals(10, count);
    }

    @Test
    void queryEncodingDuplicatesAndDecimalPrecisionArePreserved() throws Exception {
        String path = "/api/public/v1/items?market_hash_name=AK-47%20%7C%20Redline%20%28Field-Tested%29"
                + "&float_min=0.123456789012345678901234567890&phase=a%2Bb&tag=one&tag=two&empty=";
        mvc.perform(get(URI.create(path)).header("Authorization", "Bearer caller-key")
                        .header("Cookie", "session=caller-cookie"))
                .andExpect(status().isOk());
        CapturedRequest upstream = takeRequest();
        assertEquals(path, upstream.uri);
        assertEquals("Bearer test-server-key", upstream.authorization);
        assertNull(upstream.cookie);
    }

    @Test
    void upstreamErrorsAndRateLimitHeadersAreNotWrapped() throws Exception {
        for (int status : Arrays.asList(401, 403, 404, 409, 422, 429, 500, 503)) {
            Reply failure = new Reply(status, "{ \"code\":" + status + ",\"msg\":\"upstream\",\"data\":null,"
                    + "\"error_code\":\"UPSTREAM_CODE\",\"trace_id\":\"original-trace\","
                    + "\"details\":[{\"field\":\"body.max_price\"}] }");
            failure.headers.put("Retry-After", "17");
            failure.headers.put("X-Trace-Id", "original-trace");
            failure.headers.put("Connection", "X-Internal-Hop");
            failure.headers.put("X-Internal-Hop", "must-not-forward");
            failure.headers.put("Set-Cookie", "upstream-session=secret");
            reply.set(failure);
            mvc.perform(get("/api/public/v1/wallets/me"))
                    .andExpect(status().is(status))
                    .andExpect(content().bytes(failure.body))
                    .andExpect(header().string("Retry-After", "17"))
                    .andExpect(header().string("X-Trace-Id", "original-trace"))
                    .andExpect(header().doesNotExist("X-Internal-Hop"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            takeRequest();
        }
    }

    @Test
    void malformedJsonIsValidatedByUpstreamRatherThanLocally() throws Exception {
        reply.set(new Reply(422, "{\"error_code\":\"MALFORMED_JSON\",\"data\":null}"));
        mvc.perform(post("/api/public/v1/orders").contentType("application/json").content("{broken"))
                .andExpect(status().is(422)).andExpect(content().bytes(reply.get().body));
        assertArrayEquals("{broken".getBytes(StandardCharsets.UTF_8), takeRequest().body);
    }

    @Test
    void partialBatchSuccessAndHtmlErrorsRemainByteIdentical() throws Exception {
        Reply batch = new Reply(200, "{\"code\":200,\"data\":{\"accepted\":1,\"rejected\":1,"
                + "\"items\":[{\"accepted\":true},{\"accepted\":false,\"error_code\":\"INSUFFICIENT_BALANCE\"}]}}");
        reply.set(batch);
        mvc.perform(post("/api/public/v1/orders/quick/batch").contentType("application/json").content("{\"items\":[]}"))
                .andExpect(status().isOk()).andExpect(content().bytes(batch.body));
        takeRequest();
        Reply html = new Reply(502, "<html>upstream gateway failure</html>");
        html.headers.put("Content-Type", "text/html");
        reply.set(html);
        mvc.perform(get("/api/public/v1/orders"))
                .andExpect(status().isBadGateway()).andExpect(content().contentType("text/html"))
                .andExpect(content().bytes(html.body));
    }

    @Test
    void redirectsAreReturnedWithoutFollowingThem() throws Exception {
        Reply redirect = new Reply(307, "redirect");
        redirect.headers.put("Location", properties.getBaseUrl() + "/v1/orders/quick");
        reply.set(redirect);
        mvc.perform(post("/api/public/v1/orders").contentType("application/json").content("{}"))
                .andExpect(status().isTemporaryRedirect()).andExpect(content().bytes(redirect.body))
                .andExpect(header().string("Location", redirect.headers.get("Location")));
        assertEquals(1, received.get());
    }

    @Test
    void compressedResponsesPreserveTheirEncoding() throws Exception {
        Reply compressed = new Reply(200, "{\"data\":\"中文\"}");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(compressed.body); }
        compressed.body = buffer.toByteArray();
        compressed.headers.put("Content-Encoding", "gzip");
        reply.set(compressed);
        mvc.perform(get("/api/public/v1/items")).andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "gzip"))
                .andExpect(content().bytes(compressed.body));
    }

    @Test
    void timeoutDoesNotRetryOrClaimTheOrderFailed() throws Exception {
        properties.setReadTimeout(Duration.ofMillis(200));
        configureClient();
        Reply slow = new Reply(200, "{}");
        slow.delayMillis = 1500;
        reply.set(slow);
        mvc.perform(post("/api/public/v1/orders").contentType("application/json").content("{}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error_code").value("PROXY_UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.trace_id").isNotEmpty())
                .andExpect(jsonPath("$.success").doesNotExist());
        assertEquals(1, received.get());
    }

    @Test
    void droppedConnectionsAreNotAutomaticallyRetried() throws Exception {
        Reply disconnect = new Reply(200, "{}");
        disconnect.disconnect = true;
        reply.set(disconnect);
        mvc.perform(post("/api/public/v1/orders/quick").contentType("application/json").content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error_code").value("PROXY_UPSTREAM_UNAVAILABLE"));
        assertEquals(1, received.get());
    }

    @Test
    void missingKeyAndUndocumentedPathsNeverReachUpstream() throws Exception {
        properties.setApiKey("");
        mvc.perform(get("/api/public/v1/wallets/me"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error_code").value("PROXY_NOT_CONFIGURED"));
        mvc.perform(get("/api/public/v1/not-a-real-endpoint")).andExpect(status().isNotFound());
        assertEquals(0, received.get());
    }

    private CapturedRequest takeRequest() throws InterruptedException {
        CapturedRequest request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
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
        Reply(int status, String body) {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }
    }
}
