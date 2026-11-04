package com.csgo.tradeup.proxy.integration.xunics;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class XunicsProxyService {
    public static final String PREFIX = "/api/public";
    private static final Set<String> HOP_BY_HOP = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length"));
    private final XunicsProperties properties;
    private final CloseableHttpClient client;
    private final String baseUrl;

    public XunicsProxyService(XunicsProperties properties, CloseableHttpClient xunicsHttpClient) {
        this.properties = properties;
        this.client = xunicsHttpClient;
        URI base = URI.create(properties.getBaseUrl());
        if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid xunics.base-url");
        }
        this.baseUrl = base.toASCIIString().replaceAll("/+$", "");
    }

    public ResponseEntity<byte[]> forward(HttpServletRequest incoming) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new XunicsProxyException(503, "PROXY_NOT_CONFIGURED", "Xunics API Key 尚未配置");
        }
        // 使用原始 URI 和原始字节，不进行 DTO 绑定、数字转换或二次 URL 编码。
        String path = incoming.getRequestURI().substring(incoming.getContextPath().length());
        if (!path.startsWith(PREFIX + "/v1/")) {
            throw new XunicsProxyException(400, "PROXY_INVALID_PATH", "不支持的代理路径");
        }
        String query = incoming.getQueryString();
        URI target = URI.create(baseUrl + path.substring(PREFIX.length())
                + (query == null ? "" : "?" + query));
        RequestBuilder request = RequestBuilder.create(incoming.getMethod()).setUri(target)
                .setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey().trim())
                .setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
        copyRequestHeader(incoming, request, HttpHeaders.ACCEPT);
        copyRequestHeader(incoming, request, HttpHeaders.CONTENT_TYPE);
        copyRequestHeader(incoming, request, HttpHeaders.CONTENT_ENCODING);
        try {
            byte[] body = StreamUtils.copyToByteArray(incoming.getInputStream());
            if (body.length > 0 || "POST".equals(incoming.getMethod())) {
                request.setEntity(new ByteArrayEntity(body));
            }
            try (CloseableHttpResponse upstream = client.execute(request.build())) {
                HttpHeaders headers = responseHeaders(upstream);
                byte[] bytes = upstream.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(upstream.getEntity());
                return ResponseEntity.status(upstream.getStatusLine().getStatusCode()).headers(headers).body(bytes);
            }
        } catch (SocketTimeoutException | ConnectTimeoutException e) {
            throw new XunicsProxyException(504, "PROXY_UPSTREAM_TIMEOUT",
                    "上游请求超时，下单结果可能未知；重试须使用原 external_order_no 和相同参数");
        } catch (IOException e) {
            throw new XunicsProxyException(502, "PROXY_UPSTREAM_UNAVAILABLE",
                    "上游连接异常，下单结果可能未知；重试须使用原 external_order_no 和相同参数");
        }
    }

    private void copyRequestHeader(HttpServletRequest incoming, RequestBuilder request, String name) {
        if (incoming.getHeader(name) != null) request.setHeader(name, incoming.getHeader(name));
    }

    private HttpHeaders responseHeaders(CloseableHttpResponse upstream) {
        Set<String> excluded = new HashSet<>(HOP_BY_HOP);
        for (Header connection : upstream.getHeaders("Connection")) {
            for (String token : connection.getValue().split(",")) excluded.add(token.trim().toLowerCase(Locale.ROOT));
        }
        HttpHeaders headers = new HttpHeaders();
        for (Header header : upstream.getAllHeaders()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            // 跨域由本服务管理；上游 Cookie 不应用于代理域名。
            if (!excluded.contains(name) && !name.startsWith("access-control-") && !"set-cookie".equals(name)) {
                headers.add(header.getName(), header.getValue());
            }
        }
        return headers;
    }
}
