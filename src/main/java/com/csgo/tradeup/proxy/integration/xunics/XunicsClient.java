package com.csgo.tradeup.proxy.integration.xunics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;

/** 仅处理上游鉴权、HTTP 与协议解析；不接收 ServletRequest，不自动重试下单。 */
@Component
public class XunicsClient {
    private final XunicsProperties properties;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public XunicsClient(XunicsProperties properties, CloseableHttpClient xunicsHttpClient, ObjectMapper mapper) {
        this.properties = properties;
        this.httpClient = xunicsHttpClient;
        this.mapper = mapper;
        URI uri = URI.create(properties.getBaseUrl());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid xunics.base-url");
        }
        baseUrl = uri.toASCIIString().replaceAll("/+$", "");
    }

    public <T> T get(String path, Object query, TypeReference<T> responseType) {
        return exchange("GET", path, query, null, responseType);
    }

    public <T> T post(String path, Object body, TypeReference<T> responseType) {
        return exchange("POST", path, null, body, responseType);
    }

    private <T> T exchange(String method, String path, Object query, Object body, TypeReference<T> type) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new XunicsProxyException(503, "PROXY_NOT_CONFIGURED", "Xunics API Key 尚未配置");
        }
        try {
            URIBuilder uri = new URIBuilder(baseUrl + path);
            if (query != null) {
                Iterator<Map.Entry<String, JsonNode>> fields = mapper.valueToTree(query).fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!field.getValue().isNull()) uri.addParameter(field.getKey(), field.getValue().asText());
                }
            }
            RequestBuilder request = RequestBuilder.create(method).setUri(uri.build())
                    .setHeader("Authorization", "Bearer " + properties.getApiKey().trim())
                    .setHeader("Accept", "application/json");
            if (body != null) request.setEntity(new ByteArrayEntity(mapper.writeValueAsBytes(body), ContentType.APPLICATION_JSON));
            try (CloseableHttpResponse response = httpClient.execute(request.build())) {
                int status = response.getStatusLine().getStatusCode();
                byte[] bytes = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                JsonNode envelope;
                try {
                    envelope = mapper.readTree(bytes);
                } catch (IOException invalid) {
                    throw new XunicsProxyException(status >= 400 ? status : 502, "PROXY_INVALID_RESPONSE", "上游返回了非 JSON 响应");
                }
                if (envelope == null || !envelope.isObject()) {
                    throw new XunicsProxyException(502, "PROXY_INVALID_RESPONSE", "上游返回格式不正确");
                }
                if (status < 200 || status >= 300 || envelope.path("code").asInt(-1) != 200) {
                    XunicsResponse<JsonNode> failure = mapper.readValue(bytes, new TypeReference<XunicsResponse<JsonNode>>() { });
                    int errorStatus = status >= 400 ? status : failure.getCode() != null
                            && failure.getCode() >= 400 && failure.getCode() <= 599 ? failure.getCode() : 502;
                    Header retry = response.getFirstHeader("Retry-After");
                    throw new XunicsProxyException(errorStatus,
                            StringUtils.hasText(failure.getErrorCode()) ? failure.getErrorCode() : "PROXY_UPSTREAM_ERROR",
                            StringUtils.hasText(failure.getMsg()) ? failure.getMsg() : "上游请求失败")
                            .upstream(failure.getTraceId(), retry == null ? null : retry.getValue(), failure.getDetails());
                }
                if (!envelope.has("data")) throw new XunicsProxyException(502, "PROXY_INVALID_RESPONSE", "上游响应缺少 data");
                try {
                    JavaType wrapper = mapper.getTypeFactory().constructParametricType(XunicsResponse.class,
                            mapper.getTypeFactory().constructType(type.getType()));
                    XunicsResponse<T> result = mapper.readValue(bytes, wrapper);
                    return result.getData();
                } catch (IOException invalid) {
                    throw new XunicsProxyException(502, "PROXY_INVALID_RESPONSE", "上游响应字段类型不正确");
                }
            }
        } catch (SocketTimeoutException | ConnectTimeoutException e) {
            throw new XunicsProxyException(504, "PROXY_UPSTREAM_TIMEOUT",
                    "上游请求超时，下单结果可能未知；重试须使用原 external_order_no 和相同参数");
        } catch (IOException e) {
            throw new XunicsProxyException(502, "PROXY_UPSTREAM_UNAVAILABLE",
                    "上游连接异常，下单结果可能未知；重试须使用原 external_order_no 和相同参数");
        } catch (URISyntaxException e) {
            throw new XunicsProxyException(400, "PROXY_INVALID_REQUEST", "请求路径或查询参数格式错误");
        }
    }
}
