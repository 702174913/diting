package com.diting.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交识别任务（开发文档 §7.2；S16 扩展多文件）。
 *
 * uploadId：单个上传会话（兼容旧客户端；音频或图片都行）。
 * uploadIds：一次提交的多个会话（最多 1 个音频 + 6 张图片），顺序即图片在记录里的顺序。
 *          两者同时传时，uploadId 会被并入 uploadIds 并排在最前。
 * declaredDurationSec：客户端声明的时长，只用于「超过政策上限」的预检；
 *          真实时长以云端返回为准（S05 回填），这里不作为事实保存到报告。
 * idempotencyKey：可选；不传时按上传会话派生，保证一次提交只登记一个任务。
 */
public record TaskSubmitRequest(
    Long uploadId,

    List<Long> uploadIds,

    @PositiveOrZero(message = "declaredDurationSec 不能为负") Integer declaredDurationSec,

    @Size(max = 64, message = "idempotencyKey 最长 64 位") String idempotencyKey
) {

    /** 有效上传列表：uploadIds 优先，uploadId 并入最前；空列表由服务层给出明确 422 */
    public List<Long> effectiveUploadIds() {
        List<Long> merged = new ArrayList<>();
        if (uploadIds != null) {
            for (Long id : uploadIds) {
                if (id != null && !merged.contains(id)) {
                    merged.add(id);
                }
            }
        }
        if (uploadId != null && !merged.contains(uploadId)) {
            merged.add(0, uploadId);
        }
        return merged;
    }
}
