package com.diting.dto;

import com.diting.service.UploadSessionService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 上传签名结果。
 * method 与 headers 必须严格照用：协议是原始字节 PUT，不是 multipart 表单。
 */
public record UploadSignResponse(
    Long uploadId,
    String objectKey,
    String uploadUrl,
    String method,
    Map<String, String> headers,
    LocalDateTime expiresAt,
    LocalDateTime cleanupAfter
) {

    public static UploadSignResponse of(UploadSessionService.SignResult result) {
        return new UploadSignResponse(
            result.uploadId(),
            result.objectKey(),
            result.presigned().url(),
            result.presigned().method(),
            result.presigned().headers(),
            LocalDateTime.ofInstant(result.presigned().expiresAt(), java.time.ZoneId.systemDefault()),
            result.cleanupAfter()
        );
    }
}
