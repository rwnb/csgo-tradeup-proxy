package com.csgo.tradeup.proxy.controller.base;

import lombok.Data;

@Data
public class PageQuery {

    /** 页码 */
    private Long page = 1L;

    /** 每页大小 */
    private Long size = 10L;

    /** 最大分页限制（防止刷接口） */
    private static final long MAX_SIZE = 500L;

    public long getPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public long getSize() {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, MAX_SIZE);
    }
}
