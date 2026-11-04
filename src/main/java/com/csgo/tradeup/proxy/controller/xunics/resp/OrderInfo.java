package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("OrderInfo")
public class OrderInfo {
    @ApiModelProperty(value = "Xunics 平台订单号")
    @JsonProperty("order_no")
    private String orderNo;

    @ApiModelProperty(value = "业务订单号；重试时保持订单号和全部参数一致")
    @JsonProperty("external_order_no")
    private String externalOrderNo;

    @ApiModelProperty(value = "listing = a specific listing pinned by quote, quick = by name, cheapest first")
    @JsonProperty("kind")
    private String kind;

    @ApiModelProperty(value = "交付方式：auto 自动，manual 人工；省略时由上游默认处理")
    @JsonProperty("delivery_type")
    private String deliveryType;

    @ApiModelProperty(value = "有效报价 ID")
    @JsonProperty("quote_id")
    private String quoteId;

    @ApiModelProperty(value = "状态，取值遵循 Xunics 协议")
    @JsonProperty("status")
    private String status;

    @ApiModelProperty(value = "资金状态：held/captured/released/refunded")
    @JsonProperty("payment_status")
    private String paymentStatus;

    @ApiModelProperty(value = "币种 USD")
    @JsonProperty("currency")
    private String currency;

    @ApiModelProperty(value = "Quick orders carry only the name and delivery method until the source picks a listing; trade details are filled in afterwards")
    @JsonProperty("item")
    private ListingInfo item;

    @ApiModelProperty(value = "查询时的含费报价")
    @JsonProperty("quoted_price")
    private String quotedPrice;

    @ApiModelProperty(value = "含全部费用的美元价格上限，使用十进制字符串")
    @JsonProperty("max_price")
    private String maxPrice;

    @ApiModelProperty(value = "确认后的实际含费成交价")
    @JsonProperty("actual_price")
    private String actualPrice;

    @ApiModelProperty(value = "实际扣款金额")
    @JsonProperty("paid_amount")
    private String paidAmount;

    @ApiModelProperty(value = "已退金额")
    @JsonProperty("refunded_amount")
    private String refundedAmount;

    @ApiModelProperty(value = "是否仍在等待退款")
    @JsonProperty("refund_pending")
    private Boolean refundPending;

    @ApiModelProperty(value = "收货方需要接受的 Steam 交易报价 ID")
    @JsonProperty("steam_offer_id")
    private String steamOfferId;

    @ApiModelProperty(value = "订单失败原因代码")
    @JsonProperty("failure_code")
    private String failureCode;

    @ApiModelProperty(value = "创建时间，保留带时区的 ISO 8601 格式")
    @JsonProperty("created_at")
    private String createdAt;

    @ApiModelProperty(value = "更新时间，保留带时区的 ISO 8601 格式")
    @JsonProperty("updated_at")
    private String updatedAt;

    @ApiModelProperty(value = "完成时间，未完成时为空")
    @JsonProperty("completed_at")
    private String completedAt;

    @ApiModelProperty(value = "已知的交易保护期结束时间")
    @JsonProperty("reversible_until")
    private String reversibleUntil;

}
