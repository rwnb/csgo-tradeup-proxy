package com.csgo.tradeup.proxy.controller.xunics.resp;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import java.util.List;

/** 错误的补充数据，外层仍使用统一 ResultInfo。 */
@Data
public class XunicsErrorInfo {
    @ApiModelProperty("上游追踪 ID；本地错误则为代理追踪 ID")
    private String traceId;
    @ApiModelProperty("参数校验错误明细")
    private List<XunicsErrorDetail> details;
}
