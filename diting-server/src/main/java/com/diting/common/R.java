package com.diting.common;

import lombok.Data;

/**
 * 统一响应信封 {code, message, data, symbol}。
 * 成功 code=0；失败时 code 与 HTTP 状态码一致，symbol 为业务错误标识（成功为 null）。
 */
@Data
public class R<T> {

    private int code;
    private String message;
    private T data;
    /** 业务错误标识，如 LOGIN_FAILED / QUOTA_EXCEEDED；成功为 null */
    private String symbol;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ResultCode rc) {
        R<T> r = new R<>();
        r.code = rc.getCode();
        r.message = rc.getMessage();
        r.symbol = rc.getSymbol();
        return r;
    }

    public static <T> R<T> fail(ResultCode rc, String message) {
        R<T> r = fail(rc);
        r.message = message;
        return r;
    }
}
