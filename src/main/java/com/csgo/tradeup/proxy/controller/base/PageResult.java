package com.csgo.tradeup.proxy.controller.base;

import lombok.Data;
import java.util.List;

@Data
public class PageResult<T> {

    /** 当前页 */
    private long page;

    /** 每页大小 */
    private long size;

    /** 总条数 */
    private long total;

    /** 总页数 */
    private long pages;

    /** 数据 */
    private List<T> list;

    public static <T> PageResult<T> of(long page, long size, long total, List<T> list) {
        PageResult<T> r = new PageResult<>();
        r.setPage(page);
        r.setSize(size);
        r.setTotal(total);
        r.setList(list);
        r.setPages((total + size - 1) / size);
        return r;
    }
}