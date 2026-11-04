package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.integration.xunics.XunicsProxyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 只处理本地代理异常，避免与原项目 ResultInfo 混用。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = XunicsProxyController.class)
public class XunicsProxyExceptionHandler {
    @ExceptionHandler(XunicsProxyException.class)
    public ResponseEntity<Map<String, Object>> proxyError(XunicsProxyException e) {
        return error(e.getStatus(), e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException e) {
        return error(400, "PROXY_INVALID_REQUEST", "代理请求格式错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return error(500, "PROXY_INTERNAL_ERROR", "代理内部异常");
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("msg", message);
        body.put("data", null);
        body.put("error_code", code);
        body.put("trace_id", UUID.randomUUID().toString());
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
