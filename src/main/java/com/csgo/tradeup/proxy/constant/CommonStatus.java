package com.csgo.tradeup.proxy.constant;

import lombok.Getter;

@Getter
public enum CommonStatus {
    CAN_USE(1, "正常"),
    NO_USE(0, "禁用");


    CommonStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    private final Integer code;
    private final String description;

}
