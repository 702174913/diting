package com.diting.dto;

import com.diting.entity.UploadSession;
import jakarta.validation.constraints.NotNull;

/**
 * 上传完成校验。
 * durationMs 说明：上传阶段只验证对象存在、大小与真实格式；
 * 真实时长由 S05 用听悟返回结果回填并交叉校验，这里为 null 不伪造数值。
 */
public record UploadCompleteResponse(
    Long uploadId,
    String status,
    Long sizeBytes,
    Long durationMs,
    String mediaFormat,
    String filename,
    String contentHash
) {

    public static UploadCompleteResponse of(UploadSession session) {
        return new UploadCompleteResponse(
            session.getId(),
            session.getStatus(),
            session.getSizeBytes(),
            session.getDurationMs(),
            session.getMediaFormat(),
            session.getFilename(),
            session.getContentHash()
        );
    }

    /** 请求体：只带 uploadId，objectKey 由服务端按会话取出 */
    public record Request(@NotNull(message = "uploadId 不能为空") Long uploadId) {
    }
}
