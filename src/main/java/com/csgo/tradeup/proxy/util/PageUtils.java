package com.csgo.tradeup.proxy.util;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csgo.tradeup.proxy.controller.base.PageResult;

import org.springframework.beans.BeanUtils;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PageUtils {

    /**
     * 原样返回（兼容旧代码）
     */
    public static <T> PageResult<T> build(Page<T> page) {
        return PageResult.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getRecords()
        );
    }

    /**
     * VO转换（Bean拷贝）
     */
    public static <T, R> PageResult<R> build(Page<T> page, Class<R> clazz) {

        List<R> list = page.getRecords().stream()
                .map(item -> {
                    try {
                        R r = clazz.getDeclaredConstructor().newInstance();
                        BeanUtils.copyProperties(item, r);
                        return r;
                    } catch (Exception e) {
                        throw new RuntimeException("VO转换失败", e);
                    }
                })
                .collect(Collectors.toList());

        return PageResult.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                list
        );
    }

    /**
     * 自定义转换（最灵活🔥）
     */
    public static <T, R> PageResult<R> build(Page<T> page, Function<T, R> mapper) {

        List<R> list = page.getRecords().stream()
                .map(mapper)
                .collect(Collectors.toList());

        return PageResult.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                list
        );
    }
}