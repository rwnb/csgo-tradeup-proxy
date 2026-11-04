package com.csgo.tradeup.proxy.integration.xunics;

import lombok.Getter;
import com.csgo.tradeup.proxy.controller.xunics.resp.XunicsErrorDetail;
import java.util.List;

/** 封装上游与本地代理异常，由 Controller Advice 转换为统一返回格式。 */
@Getter
public class XunicsProxyException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private String traceId;
    private String retryAfter;
    private List<XunicsErrorDetail> details;

    public XunicsProxyException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public XunicsProxyException upstream(String traceId, String retryAfter, List<XunicsErrorDetail> details) {
        this.traceId = traceId;
        this.retryAfter = retryAfter;
        this.details = details;
        return this;
    }
}
