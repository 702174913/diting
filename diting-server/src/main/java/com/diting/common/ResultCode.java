package com.diting.common;

import lombok.Getter;

/**
 * 统一错误枚举：HTTP 语义码 + 业务 symbol。
 * 约定（开发文档 §7.1）：401 未登录、403 无权限、404 资源不存在/归属不符、
 * 409 版本或状态冲突、422 格式/参数不合法、429 额度或限流、503 未配置/服务不可用。
 * 同一 code 可对应多个业务 symbol，客户端优先按 symbol 判分支、按 code 判通用处理。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success", null),

    BAD_REQUEST(400, "请求参数错误", "BAD_REQUEST"),
    UNAUTHORIZED(401, "未登录或登录已过期", "UNAUTHORIZED"),
    /** 登录失败统一文案，不泄露账号是否存在 */
    LOGIN_FAILED(401, "账号或密码错误", "LOGIN_FAILED"),

    FORBIDDEN(403, "无权访问", "FORBIDDEN"),
    ACCOUNT_DISABLED(403, "账号已被禁用", "ACCOUNT_DISABLED"),

    NOT_FOUND(404, "资源不存在", "NOT_FOUND"),
    /** 原件超过保留期（S13 使用） */
    AUDIO_EXPIRED(404, "原始录音已超过保留期", "AUDIO_EXPIRED"),

    CONFLICT(409, "资源状态冲突，请刷新后重试", "CONFLICT"),
    /** 需要先确认说话人角色（S08 使用） */
    ROLE_UNCONFIRMED(409, "请先确认说话人角色", "ROLE_UNCONFIRMED"),
    /** 任务仍在处理（S06 使用） */
    PROCESSING(409, "任务正在处理中，请稍后重试", "PROCESSING"),

    UNPROCESSABLE(422, "参数格式不合法", "UNPROCESSABLE"),
    /** 任务终态失败（S06 使用） */
    FAILED(422, "任务处理失败", "FAILED"),

    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试", "TOO_MANY_REQUESTS"),
    /** 免费额度用尽（S03 使用） */
    QUOTA_EXCEEDED(429, "今日可用份数已用完", "QUOTA_EXCEEDED"),

    INTERNAL_ERROR(500, "服务器内部错误", "INTERNAL_ERROR"),
    /** 云服务凭据/配置缺失，禁止返回模拟数据（S04/S05 使用） */
    NOT_CONFIGURED(503, "服务未配置", "NOT_CONFIGURED");

    private final int code;
    private final String message;
    private final String symbol;

    ResultCode(int code, String message, String symbol) {
        this.code = code;
        this.message = message;
        this.symbol = symbol;
    }
}
