package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("XunicsErrorDetail")
public class XunicsErrorDetail {
    @ApiModelProperty(value = "错误字段路径")
    @JsonProperty("field")
    private String field;

    @ApiModelProperty(value = "校验错误类型")
    @JsonProperty("type")
    private String type;

    @ApiModelProperty(value = "错误说明")
    @JsonProperty("msg")
    private String msg;

}
