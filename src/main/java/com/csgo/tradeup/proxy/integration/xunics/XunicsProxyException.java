package com.csgo.tradeup.proxy.integration.xunics;

import lombok.Getter;

/** 仅表示代理本身的失败；上游 HTTP 错误响应直接透传。 */
@Getter
public class XunicsProxyException extends RuntimeException {
    private final int status;
    private final String errorCode;

    public XunicsProxyException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
}
