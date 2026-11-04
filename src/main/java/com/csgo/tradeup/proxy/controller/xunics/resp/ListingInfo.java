package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import java.util.List;

@Data
@ApiModel("ListingInfo")
public class ListingInfo {
    @ApiModelProperty(value = "Steam 市场完整名称，精确匹配")
    @JsonProperty("market_hash_name")
    private String marketHashName;

    @ApiModelProperty(value = "本地化商品名称")
    @JsonProperty("name")
    private String name;

    @ApiModelProperty(value = "饰品款式阶段")
    @JsonProperty("phase")
    private String phase;

    @ApiModelProperty(value = "交付方式：auto 自动，manual 人工；省略时由上游默认处理")
    @JsonProperty("delivery_type")
    private String deliveryType;

    @ApiModelProperty(value = "精确磨损字符串，未知时为空")
    @JsonProperty("float_value")
    private String floatValue;

    @ApiModelProperty(value = "图案种子")
    @JsonProperty("paint_seed")
    private Long paintSeed;

    @ApiModelProperty(value = "涂装编号")
    @JsonProperty("paint_index")
    private Long paintIndex;

    @ApiModelProperty(value = "自定义名称标签")
    @JsonProperty("name_tag")
    private String nameTag;

    @ApiModelProperty(value = "已贴贴纸")
    @JsonProperty("stickers")
    private List<StickerInfo> stickers;

    @ApiModelProperty(value = "已挂挂件")
    @JsonProperty("charms")
    private List<CharmInfo> charms;

}
