package com.diting.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常 → 统一错误信封。
 * HTTP 状态码与信封 code 保持一致（401/403/404/409/422/429/503），
 * 客户端拦截器需要同时处理 HTTP 失败与信封失败。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> biz(BizException e) {
        return ResponseEntity.status(httpStatus(e.getResultCode()))
            .body(R.fail(e.getResultCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
        HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<R<Void>> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(R.fail(ResultCode.BAD_REQUEST, describe(e)));
    }

    /**
     * 路径没匹配到任何接口，也没匹配到静态资源。
     * Spring 会抛 NoResourceFoundException；若落到下面的 Exception 兜底，
     * 就会变成 500「服务器内部错误」——把「地址写错了」误报成「服务挂了」，
     * 线上排查时非常误导。典型场景：nginx 的 proxy_pass 末尾多写了一个斜杠，
     * 把 /api 前缀吃掉，后端收到 /admin/login 就会显示 500。
     * 这里按语义返回 404，只记一行 warn，不掩盖真实错误。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> notFound(NoResourceFoundException e) {
        log.warn("no handler matched: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(R.fail(ResultCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> unknown(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(R.fail(ResultCode.INTERNAL_ERROR));
    }

    /** 业务码本身就是 HTTP 语义码；0 或非法值兜底为 200/500 */
    private static HttpStatus httpStatus(ResultCode rc) {
        HttpStatus status = HttpStatus.resolve(rc.getCode());
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    /**
     * 参数类异常统一给可读文案：缺参数、类型不匹配过去会掉到 500，
     * 客户端只能看到“服务器内部错误”，排查时也没有线索。
     */
    private static String describe(Exception e) {
        if (e instanceof MissingServletRequestParameterException missing) {
            return "缺少必填参数：" + missing.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return "参数类型不正确：" + mismatch.getName();
        }
        if (e instanceof ConstraintViolationException violation) {
            return violation.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse(ResultCode.BAD_REQUEST.getMessage());
        }
        return ResultCode.BAD_REQUEST.getMessage();
    }
}
