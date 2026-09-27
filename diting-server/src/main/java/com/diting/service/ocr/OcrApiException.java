package com.diting.service.ocr;

import java.util.Set;

/**
 * OCR 云端调用失败：带上**云端错误码**与「是否值得重试」。
 *
 * 为什么单独一个异常：以前不管什么失败都只好记成常量 `INTERNAL_ERROR`/`OCR_UNCERTAIN`，
 * 上线时「图片识别失败」分不清是**服务没开通/没授权**还是图坏了。
 *
 * 分类依据阿里云 ocr-api 2021-07-07 官方错误码表（核验日期 2026-09-27）：
 * - **可重试**：Throttling.*、algorithmError、ServiceUnavailable、AlgorithmTimeout、ServiceTimeout、
 *   imageTimeOut，以及网络中断/超时（没有错误码）；
 * - **不可重试**（同一张图重试只会重复失败并被重复计费）：noPermission、ocrServiceNotOpen、
 *   OcrServiceExpired、unsupportedImageFormat、illegalImageSize、exceededImageContent、
 *   illegalSignature、MissingAccessKeyId、invalidStsToken、MissingImageUrl、imageUrlOrBodyEmpty、
 *   deprecatedApi、notSafeImageUrl、unmatchedImageType。
 *
 * **未知错误码按可重试处理**：多花几次调用的代价远小于把用户任务误判死。
 */
public class OcrApiException extends RuntimeException {

    /** 官方限流码前缀（Throttling、Throttling.User、Throttling.Api…） */
    private static final String THROTTLING_PREFIX = "Throttling";

    /** 明确「重试无用」的错误码 */
    private static final Set<String> PERMANENT = Set.of(
        "noPermission",
        "ocrServiceNotOpen",
        "OcrServiceExpired",
        "unsupportedImageFormat",
        "illegalImageSize",
        "exceededImageContent",
        "illegalSignature",
        "InvalidSignatureVerssion",
        "MissingAccessKeyId",
        "invalidStsToken",
        "MissingImageUrl",
        "imageUrlOrBodyEmpty",
        "deprecatedApi",
        "notSafeImageUrl",
        "unmatchedImageType",
        "MissingSignature",
        "MissingImageType");

    /** 明确「过一会儿能好」的错误码 */
    private static final Set<String> RETRYABLE = Set.of(
        "algorithmError",
        "AlgorithmTimeout",
        "ServiceUnavailable",
        "ServiceTimeout",
        "imageTimeOut",
        "InternalError");

    private final String code;
    private final boolean retryable;

    private OcrApiException(String code, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** 云端返回了错误码（`Code` 字段或 SDK 异常）：按官方错误码表分类 */
    public static OcrApiException ofCode(String code, String cloudMessage) {
        return new OcrApiException(code, isRetryableCode(code),
            "OCR 云端失败（code=" + code + (cloudMessage == null || cloudMessage.isBlank()
                ? "" : "：" + clip(cloudMessage)) + "）", null);
    }

    /** 网络中断、超时、响应体缺失：结果不确定，值得重试 */
    public static OcrApiException transientFailure(String message, Throwable cause) {
        return new OcrApiException(null, true, message, cause);
    }

    /** 云端错误码；网络/超时类失败为 null */
    public String getCode() {
        return code;
    }

    /** 是否值得重试（重试会再次计费） */
    public boolean isRetryable() {
        return retryable;
    }

    /** 落库用的错误标记：有错误码就用错误码本身，否则用不确定标记 */
    public String errorMark() {
        return code == null || code.isBlank() ? "OCR_UNCERTAIN" : clip(code);
    }

    public static boolean isRetryableCode(String code) {
        if (code == null || code.isBlank()) {
            return true;
        }
        if (RETRYABLE.contains(code) || code.startsWith(THROTTLING_PREFIX)) {
            return true;
        }
        return !PERMANENT.contains(code);
    }

    /** 错误码/云侧文案只留短标记，避免把整段响应写进日志与数据库 */
    private static String clip(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }
}
