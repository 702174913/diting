package com.diting.service.tingwu;

/** CreateTask 明确未受理：仅限可判定的 4xx 或响应中的非成功业务码；不能把超时/5xx归入此类。 */
public class CloudSubmitRejectedException extends RuntimeException {
    private final String safeCode;

    public CloudSubmitRejectedException(String code) {
        super("听悟明确拒绝提交");
        String sanitized = code == null ? "" : code.replaceAll("[^a-zA-Z0-9._-]", "");
        this.safeCode = sanitized.isEmpty() ? "REMOTE_REJECTED"
            : sanitized.substring(0, Math.min(64, sanitized.length()));
    }

    public String safeCode() {
        return safeCode;
    }
}
