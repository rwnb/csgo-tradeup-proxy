package com.csgo.tradeup.proxy.controller.xunics.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import javax.validation.constraints.*;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel("ItemQueryParam")
public class ItemQueryParam implements XunicsQueryParam {
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

    @ApiModelProperty(value = "最低磨损，包含边界，范围 0 至 1", name = "float_min", required = false)
    @JsonProperty("float_min")
    @DecimalMin("0")
    @DecimalMax("1")
    private String floatMin;

    @ApiModelProperty(value = "最高磨损，包含边界，范围 0 至 1", name = "float_max", required = false)
    @JsonProperty("float_max")
    @DecimalMin("0")
    @DecimalMax("1")
    private String floatMax;

    @ApiModelProperty(value = "含全部费用的美元价格上限，使用十进制字符串", name = "max_price", required = false)
    @JsonProperty("max_price")
    @Pattern(regexp = "[0-9]+(\\.[0-9]+)?", message = "必须为十进制金额字符串")
    @DecimalMin(value = "0", inclusive = false)
    private String maxPrice;

    @ApiModelProperty(value = "页码，从 1 开始", name = "page", required = false, example = "1")
    @JsonProperty("page")
    @Min(1)
    @Max(10000)
    private Long page;

    @ApiModelProperty(value = "每页条数", name = "size", required = false, example = "20")
    @JsonProperty("size")
    @Min(1)
    @Max(100)
    private Long size;

}
