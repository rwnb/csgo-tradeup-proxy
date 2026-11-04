package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("WalletLedgerInfo")
public class WalletLedgerInfo {
    @ApiModelProperty(value = "流水 ID，按字符串处理")
    @JsonProperty("id")
    private String id;

    @ApiModelProperty(value = "Top-up, adjustment, hold, capture, release or refund")
    @JsonProperty("kind")
    private String kind;

    @ApiModelProperty(value = "Xunics 平台订单号")
    @JsonProperty("order_no")
    private String orderNo;

    @ApiModelProperty(value = "本次操作或冻结金额")
    @JsonProperty("amount")
    private String amount;

    @ApiModelProperty(value = "总余额变化，正数增加、负数减少")
    @JsonProperty("balance_delta")
    private String balanceDelta;

    @ApiModelProperty(value = "冻结余额变化")
    @JsonProperty("locked_delta")
    private String lockedDelta;

    @ApiModelProperty(value = "变动前总余额")
    @JsonProperty("balance_before")
    private String balanceBefore;

    @ApiModelProperty(value = "变动前冻结余额")
    @JsonProperty("locked_before")
    private String lockedBefore;

    @ApiModelProperty(value = "变动后总余额")
    @JsonProperty("balance_after")
    private String balanceAfter;

    @ApiModelProperty(value = "变动后冻结余额")
    @JsonProperty("locked_after")
    private String lockedAfter;

    @ApiModelProperty(value = "创建时间，保留原时区")
    @JsonProperty("created_time")
    private String createdTime;

}
