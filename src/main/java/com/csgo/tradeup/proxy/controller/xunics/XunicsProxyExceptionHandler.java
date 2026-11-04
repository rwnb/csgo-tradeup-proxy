package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import com.csgo.tradeup.proxy.controller.xunics.resp.XunicsErrorDetail;
import com.csgo.tradeup.proxy.controller.xunics.resp.XunicsErrorInfo;
import com.csgo.tradeup.proxy.integration.xunics.XunicsProxyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Xunics 错误统一转换为 back 的 ResultInfo，不返回上游外层协议。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.csgo.tradeup.proxy.controller.xunics")
public class XunicsProxyExceptionHandler {
    @ExceptionHandler(XunicsProxyException.class)
    public ResponseEntity<ResultInfo<XunicsErrorInfo>> upstream(XunicsProxyException e) {
        return error(e.getStatus(), e.getErrorCode(), e.getMessage(), e.getTraceId(), e.getRetryAfter(), e.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResultInfo<XunicsErrorInfo>> invalidBody(MethodArgumentNotValidException e) {
        return validation(e.getBindingResult(), "body.");
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResultInfo<XunicsErrorInfo>> invalidQuery(BindException e) {
        return validation(e.getBindingResult(), "query.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ResultInfo<XunicsErrorInfo>> invalid(Exception e) {
        return error(400, "INVALID_ARGUMENT", "请求参数格式错误", null, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultInfo<XunicsErrorInfo>> unexpected(Exception e) {
        return error(500, "PROXY_INTERNAL_ERROR", "代理内部异常", null, null, null);
    }

    private ResponseEntity<ResultInfo<XunicsErrorInfo>> validation(BindingResult binding, String prefix) {
        List<XunicsErrorDetail> details = binding.getFieldErrors().stream().map(field -> {
            XunicsErrorDetail detail = new XunicsErrorDetail();
            detail.setField(prefix + field.getField().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase());
            detail.setType(field.getCode());
            detail.setMsg(field.getDefaultMessage());
            return detail;
        }).collect(Collectors.toList());
        return error(400, "INVALID_ARGUMENT", "请求参数校验失败", null, null, details);
    }

    private ResponseEntity<ResultInfo<XunicsErrorInfo>> error(int status, String code, String message,
            String traceId, String retryAfter, List<XunicsErrorDetail> details) {
        String trace = traceId == null ? UUID.randomUUID().toString() : traceId;
        XunicsErrorInfo data = new XunicsErrorInfo();
        data.setTraceId(trace);
        data.setDetails(details);
        ResultInfo<XunicsErrorInfo> result = ResultHelper.failResultInfo(status, message);
        result.setErrorCode(code);
        result.setData(data);
        if (details != null && !details.isEmpty()) result.setField(details.get(0).getField());
        if (retryAfter != null) {
            try { result.setRetryAfter(Integer.valueOf(retryAfter)); }
            catch (NumberFormatException ignored) { /* HTTP 日期仍保留在响应头。 */ }
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header("Cache-Control", "no-store").header("X-Trace-Id", trace);
        if (retryAfter != null) response.header("Retry-After", retryAfter);
        return response.body(result);
    }
}
