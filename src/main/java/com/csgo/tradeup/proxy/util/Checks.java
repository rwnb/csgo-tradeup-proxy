package com.csgo.tradeup.proxy.util;

import com.csgo.tradeup.proxy.config.exception.BizException;
import com.csgo.tradeup.proxy.constant.CommonStatus;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;

public final class Checks {
    private Checks() { }
    public static void text(String value, int max, String label) {
        if (!StringUtils.hasText(value) || value.length() > max) throw new BizException(label + "不能为空且长度不能超过" + max);
    }
    public static void id(Long id, String label) { if (id == null || id <= 0) throw new BizException(label + "不合法"); }
    public static void password(String password) {
        if (!StringUtils.hasText(password) || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BizException("密码至少8个字符，UTF-8编码不能超过72字节");
    }
    public static void status(Integer status) {
        if (!CommonStatus.CAN_USE.getCode().equals(status) && !CommonStatus.NO_USE.getCode().equals(status))
            throw new BizException("状态仅支持0或1");
    }
}
