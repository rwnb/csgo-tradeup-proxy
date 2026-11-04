package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("ItemQuoteInfo")
public class ItemQuoteInfo {
    @ApiModelProperty(value = "有效报价 ID")
    @JsonProperty("quote_id")
    private String quoteId;

    @ApiModelProperty(value = "Item details and attributes")
    @JsonProperty("item")
    private ListingInfo item;

    @ApiModelProperty(value = "币种 USD")
    @JsonProperty("currency")
    private String currency;

    @ApiModelProperty(value = "美元含费报价，保留原始十进制字符串")
    @JsonProperty("price")
    private String price;

    @ApiModelProperty(value = "报价生成时间")
    @JsonProperty("quoted_at")
    private String quotedAt;

    @ApiModelProperty(value = "报价失效时间")
    @JsonProperty("expires_at")
    private String expiresAt;

    @ApiModelProperty(value = "报价对应的数据时间，可能存在延迟")
    @JsonProperty("data_at")
    private String dataAt;

}
