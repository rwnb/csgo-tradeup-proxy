package com.csgo.tradeup.proxy.controller.template;



public class ResultHelper {
    /**
     * 创建成功结果集
     *
     * @param data
     * @return
     */
    public static <T> ResultInfo<T> successResultInfo(T data) {
        return new ResultInfo<>(Boolean.TRUE, ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getDesc(), data);

    }
    public static <T> ResultInfo<T> successResultInfo(String msg, T data) {
        return new ResultInfo<>(Boolean.TRUE, ResultCode.SUCCESS.getCode(), msg, data);

    }

    /**
     * 创建失败的结果集
     *
     * @param code
     * @param message
     * @return
     */
    public static <T> ResultInfo<T> failResultInfo(int code, String message) {
        return new ResultInfo<>(Boolean.FALSE, code, message, null);
    }
}
