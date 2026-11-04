package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import java.util.List;

@Data
@ApiModel("BatchOrderInfo")
public class BatchOrderInfo {
    @ApiModelProperty(value = "Per-item results in request order")
    @JsonProperty("items")
    private List<BatchOrderItemInfo> items;

    @ApiModelProperty(value = "受理结果或受理数量")
    @JsonProperty("accepted")
    private Long accepted;

    @ApiModelProperty(value = "拒绝数量")
    @JsonProperty("rejected")
    private Long rejected;

}
