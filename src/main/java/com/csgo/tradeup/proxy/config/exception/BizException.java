package com.csgo.tradeup.proxy.config.exception;

import com.csgo.tradeup.proxy.controller.template.ResultCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;
    public BizException(String message) { this(ResultCode.BAD_REQUEST, message); }
    public BizException(ResultCode code, String message) { super(message); this.code = code.getCode(); }
}
