package com.csgo.tradeup.proxy.controller.template;

import lombok.Data;

import java.io.Serializable;

@Data
public class ResultInfo implements Serializable {
    private static final long serialVersionUID = -8220880361859927361L;
    /**
     * 请求结果
     */
    private boolean success;

    /**
     * 结果码
     */
    private int code;
    /**
     * 提示信息
     */
    private String message;

    /**
     * 结果集
     */
    private Object data;


    /**
     * 构造函数
     *
     * @param success
     * @param code
     * @param message
     * @param data
     */
    public ResultInfo(boolean success, int code, String message, Object data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }
    /** C端稳定错误码；其他接口不输出该字段。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String errorCode;
    /** 对应表单字段。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String field;
    /** 重试等待秒数。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer retryAfter;


}
