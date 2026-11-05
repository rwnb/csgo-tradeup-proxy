package com.csgo.tradeup.proxy.controller.xunics.param;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

/** 下单请求不能静默忽略未知字段，避免调用方误以为采购条件已生效。 */
abstract class StrictXunicsBody {
    @JsonAnySetter
    public void rejectUnknownField(String name, JsonNode value) {
        throw new IllegalArgumentException("Unknown order request field");
    }
}
