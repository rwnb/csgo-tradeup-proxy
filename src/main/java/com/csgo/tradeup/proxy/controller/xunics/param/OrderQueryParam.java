package com.csgo.tradeup.proxy.controller.xunics.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import javax.validation.constraints.*;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel("OrderQueryParam")
public class OrderQueryParam implements XunicsQueryParam {
    @ApiModelProperty(value = "业务订单号；重试时保持订单号和全部参数一致", name = "external_order_no", required = false)
    @JsonProperty("external_order_no")
    @Size(max = 128)
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.:/-]*$")
    private String externalOrderNo;

    @ApiModelProperty(value = "状态，取值遵循 Xunics 协议", name = "status", required = false)
    @JsonProperty("status")
    private String status;

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
