package com.csgo.tradeup.proxy.controller.xunics.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import javax.validation.constraints.*;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel("QuickOrderParam")
public class QuickOrderParam {
    @ApiModelProperty(value = "业务订单号；重试时保持订单号和全部参数一致", name = "external_order_no", required = true)
    @JsonProperty("external_order_no")
    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:/-]*$")
    private String externalOrderNo;

    @ApiModelProperty(value = "含全部费用的美元价格上限，使用十进制字符串", name = "max_price", required = true)
    @JsonProperty("max_price")
    @NotBlank
    @Pattern(regexp = "[0-9]+(\\.[0-9]+)?", message = "必须为十进制金额字符串")
    @DecimalMin(value = "0", inclusive = false)
    private String maxPrice;

    @ApiModelProperty(value = "收货 Steam 账号的 HTTPS 交易链接", name = "trade_url", required = true)
    @JsonProperty("trade_url")
    @NotBlank
    @Size(max = 512)
    @Pattern(regexp = "https://.+", message = "必须为 HTTPS Steam 交易链接")
    private String tradeUrl;

    @ApiModelProperty(value = "Steam 市场完整名称，精确匹配", name = "market_hash_name", required = true)
    @JsonProperty("market_hash_name")
    @NotBlank
    @Size(max = 255)
    private String marketHashName;

    @ApiModelProperty(value = "饰品款式阶段", name = "phase", required = false)
    @JsonProperty("phase")
    @Size(min = 1, max = 64)
    private String phase;

    @ApiModelProperty(value = "交付方式：auto 自动，manual 人工；省略时由上游默认处理", name = "delivery_type", required = false)
    @JsonProperty("delivery_type")
    @Pattern(regexp = "auto|manual")
    private String deliveryType;

}
