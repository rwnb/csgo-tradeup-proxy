package com.csgo.tradeup.proxy.controller.xunics.resp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("WalletBalanceInfo")
public class WalletBalanceInfo {
    @ApiModelProperty(value = "币种 USD")
    @JsonProperty("currency")
    private String currency;

    @ApiModelProperty(value = "总余额，包含冻结金额")
    @JsonProperty("balance")
    private String balance;

    @ApiModelProperty(value = "冻结余额")
    @JsonProperty("locked_balance")
    private String lockedBalance;

    @ApiModelProperty(value = "可用余额")
    @JsonProperty("available_balance")
    private String availableBalance;

    @ApiModelProperty(value = "钱包是否启用，不等同于采购是否可用")
    @JsonProperty("enabled")
    private Boolean enabled;

}
