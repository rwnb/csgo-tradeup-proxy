package com.csgo.tradeup.proxy.controller.xunics.param;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel("BatchQuickOrderParam")
public class BatchQuickOrderParam extends StrictXunicsBody {
    @ApiModelProperty(value = "Basket contents, 20 items maximum", name = "items", required = true)
    @JsonProperty("items")
    @NotNull
    @Valid
    @Size(min = 1, max = 20)
    private List<@NotNull QuickOrderParam> items;

}
