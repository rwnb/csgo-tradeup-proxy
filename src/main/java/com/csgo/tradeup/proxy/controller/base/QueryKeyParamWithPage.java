package com.csgo.tradeup.proxy.controller.base;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class QueryKeyParamWithPage extends PageQuery {

    /**
     * 查询关键字
     */
    private String key;

}
