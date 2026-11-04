package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("StickerInfo")
public class StickerInfo {
    @ApiModelProperty(value = "Steam 市场完整名称，精确匹配")
    @JsonProperty("market_hash_name")
    private String marketHashName;

    @ApiModelProperty(value = "Steam 图片地址")
    @JsonProperty("image_url")
    private String imageUrl;

    @ApiModelProperty(value = "槽位")
    @JsonProperty("slot")
    private Long slot;

    @ApiModelProperty(value = "横向偏移量")
    @JsonProperty("offset_x")
    private String offsetX;

    @ApiModelProperty(value = "纵向偏移量")
    @JsonProperty("offset_y")
    private String offsetY;

    @ApiModelProperty(value = "旋转角度")
    @JsonProperty("rotation")
    private String rotation;

    @ApiModelProperty(value = "贴纸磨损")
    @JsonProperty("wear")
    private String wear;

}
