package com.csgo.tradeup.proxy.config.exception;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ResultInfo> business(BizException e) { return error(e.getCode(), e.getMessage()); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MaxUploadSizeExceededException.class, MissingServletRequestPartException.class,
            org.springframework.validation.BindException.class})
    public ResponseEntity<ResultInfo> invalid(Exception e) { return error(400, "参数格式错误或上传文件过大"); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResultInfo> method(Exception e) { return error(405, "请求方法不支持"); }

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<ResultInfo> concurrent(Exception e) { return error(409, "配置正在被其他操作修改，请重新加载后重试"); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ResultInfo> conflict(Exception e) { return error(409, "记录已存在或被其他记录引用"); }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ResultInfo> unavailable(Exception e) { return error(503, "数据库或缓存暂不可用"); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultInfo> unexpected(Exception e) {
        // 不记录请求体、口令、Token 或三方响应中的敏感值。
        log.error("请求处理失败，异常类型={}", e.getClass().getName());
        return error(500, "系统异常");
    }
    private ResponseEntity<ResultInfo> error(int code, String message) {
        return ResponseEntity.status(code).body(ResultHelper.failResultInfo(code, message));
    }
}
