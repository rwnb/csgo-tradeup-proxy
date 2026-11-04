package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import java.util.List;

/** Xunics 分页数据，保留原协议字段，不替换为本地 list/pages。 */
@Data
public class XunicsPageResult<T> {
    @ApiModelProperty("记录列表")
    private List<T> items;
    @ApiModelProperty("总记录数")
    private Long total;
    @ApiModelProperty("当前页")
    private Long page;
    @ApiModelProperty("每页数量")
    private Long size;
    @JsonProperty("total_pages")
    @ApiModelProperty("总页数")
    private Long totalPages;
}
