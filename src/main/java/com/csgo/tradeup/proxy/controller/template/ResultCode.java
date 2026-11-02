package com.csgo.tradeup.proxy.controller.template;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(200, "成功"), BAD_REQUEST(400, "参数不合法"), UNAUTHORIZED(401, "请重新登录"),
    FORBIDDEN(403, "无权限访问当前功能"), NOT_FOUND(404, "记录不存在"), CONFLICT(409, "记录已存在或被引用"),
    TOO_MANY_REQUESTS(429, "操作过于频繁"), UNAVAILABLE(503, "依赖服务暂不可用"), ERROR(500, "系统异常");
    private final int code;
    private final String desc;
}
