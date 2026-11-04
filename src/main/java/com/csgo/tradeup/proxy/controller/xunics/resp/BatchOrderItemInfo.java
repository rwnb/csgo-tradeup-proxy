package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("BatchOrderItemInfo")
public class BatchOrderItemInfo {
    @ApiModelProperty(value = "业务订单号；重试时保持订单号和全部参数一致")
    @JsonProperty("external_order_no")
    private String externalOrderNo;

    @ApiModelProperty(value = "受理结果或受理数量")
    @JsonProperty("accepted")
    private Boolean accepted;

    @ApiModelProperty(value = "The order when accepted; empty when rejected")
    @JsonProperty("order")
    private OrderInfo order;

    @ApiModelProperty(value = "稳定的业务错误码")
    @JsonProperty("error_code")
    private String errorCode;

    @ApiModelProperty(value = "错误说明")
    @JsonProperty("message")
    private String message;

}
