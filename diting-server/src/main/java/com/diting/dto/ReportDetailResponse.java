package com.diting.dto;

import com.diting.entity.RecognitionTask;

import java.util.List;

/**
 * 一份完整质检记录（开发文档 §7.2）。
 *
 * 字段口径：
 * - `reportVersionId/version/status` 是本次展示的**报告版本**，`latestStatus` 是最近一次分析的执行状态
 *   （重算失败时两者不同，界面据此说明「最新一次重算失败，当前展示第 N 版」）；
 * - `flaggedCount` 按命中**句子**去重；`violationCount` 含通话级缺失项，因此两者可以不等；
 * - 未完成不返回分数（riskScore/complianceScore 为空），不伪造 0；
 * - 不返回 accuracy 之类没有来源的指标；
 * - `audioAvailable` 表示原件是否仍在保留期（缺原件不影响文本与报告可读，REPORT-08）。
 */
public record ReportDetailResponse(
    Long id,
    String taskId,
    String reportNo,
    String taskStatus,
    Integer durationSec,
    String createdAt,
    Long reportVersionId,
    Integer reportVersion,
    String status,
    String latestStatus,
    Long mappingRevision,
    List<SpeakerRole> speakerMapping,
    boolean rolesConfirmed,
    int sentenceCount,
    int flaggedCount,
    int violationCount,
    Integer riskScore,
    Integer complianceScore,
    Integer threshold,
    boolean riskFlagged,
    String publishedAt,
    boolean audioAvailable,
    /** 文本是否已脱敏（后台 OPERATOR 视角为 true） */
    boolean redacted,
    List<String> notes,
    List<SentenceResponse> sentences,
    List<ViolationResponse> violations
) {

    public record SpeakerRole(Integer speakerId, String role) {
    }

    public static String reportNo(RecognitionTask task) {
        String year = task.getCreatedAt() == null ? "0000" : String.valueOf(task.getCreatedAt().getYear());
        return String.format("DT-%s-%06d", year, task.getId());
    }
}
