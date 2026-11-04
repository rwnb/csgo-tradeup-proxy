package com.csgo.tradeup.proxy.integration.xunics;

import com.csgo.tradeup.proxy.controller.xunics.resp.XunicsErrorDetail;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/** 仅用于解析上游协议，Controller 对外使用项目统一 ResultInfo。 */
@Data
public class XunicsResponse<T> {
    private Integer code;
    private String msg;
    private T data;
    @JsonProperty("error_code")
    private String errorCode;
    @JsonProperty("trace_id")
    private String traceId;
    private List<XunicsErrorDetail> details;
}
