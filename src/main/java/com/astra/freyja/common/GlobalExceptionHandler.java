package com.astra.freyja.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeoutException(AsyncRequestTimeoutException e) {
        log.warn("异步/流式请求连接超时: {}", e.getMessage());
    }

    /**
     * SSE 客户端主动关闭页面、切换路由或网络断开后的正常异步生命周期通知。
     * 响应已经不可写，不能再返回普通 JSON，否则会触发 text/event-stream converter 二次异常。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端连接已关闭，后台任务继续执行: {}", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("请求的接口或静态资源不存在 (404): {}", e.getResourcePath());
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(404, "接口或资源不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(500, "系统异常: " + (e.getMessage() != null ? e.getMessage() : "请稍后重试")));
    }
}
