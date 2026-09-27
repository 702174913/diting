package com.diting.dto;

import com.diting.entity.Sentence;

import java.util.List;

/**
 * 记录里的单条句子：原文 + 时间 + 说话人标签与业务角色 + 命中信息。
 * 角色与 speakerId 分离：未确认时 role 为 null，界面按「说话人 A/B」展示。
 * 说明与依据来自报告版本的命中证据（调用方提供），sentences 表里的 flagged/ruleRef 只作兼容缓存。
 */
public record SentenceResponse(
    Long id,
    Integer sequence,
    Long sourceAssetId,
    Integer speakerId,
    String role,
    Long startMs,
    Long endMs,
    String text,
    Integer speechSpeed,
    List<ViolationResponse.SentenceRange> highlights,
    List<Long> ruleVersionIds,
    List<String> notes
) {

    public static SentenceResponse of(Sentence sentence, String role,
                                      List<ViolationResponse.SentenceRange> highlights,
                                      List<Long> ruleVersionIds, List<String> notes) {
        return new SentenceResponse(
            sentence.getId(),
            sentence.getSequence(),
            sentence.getSourceAssetId(),
            sentence.getSpeakerId(),
            role,
            sentence.getStartMs(),
            sentence.getEndMs(),
            sentence.getText(),
            sentence.getSpeechSpeed(),
            highlights == null ? List.of() : highlights,
            ruleVersionIds == null ? List.of() : ruleVersionIds,
            notes == null ? List.of() : notes
        );
    }
}
