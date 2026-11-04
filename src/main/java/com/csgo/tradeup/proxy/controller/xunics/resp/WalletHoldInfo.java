package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("WalletHoldInfo")
public class WalletHoldInfo {
    @ApiModelProperty(value = "Xunics 平台订单号")
    @JsonProperty("order_no")
    private String orderNo;

    @ApiModelProperty(value = "本次操作或冻结金额")
    @JsonProperty("amount")
    private String amount;

    @ApiModelProperty(value = "已扣金额")
    @JsonProperty("captured_amount")
    private String capturedAmount;

    @ApiModelProperty(value = "已退金额")
    @JsonProperty("refunded_amount")
    private String refundedAmount;

    @ApiModelProperty(value = "尚可退金额，不表示可以主动申请退款")
    @JsonProperty("refundable_amount")
    private String refundableAmount;

    @ApiModelProperty(value = "状态，取值遵循 Xunics 协议")
    @JsonProperty("status")
    private String status;

    @ApiModelProperty(value = "创建时间，保留原时区")
    @JsonProperty("created_time")
    private String createdTime;

    @ApiModelProperty(value = "最后更新时间")
    @JsonProperty("updated_time")
    private String updatedTime;

}
