package com.diting.dto;

import java.util.List;

/**
 * 一条命中/缺失证据：引用规则版本，带原文高亮区间（UTF-16、左闭右开）。
 * 通话级缺失（must-have 未出现、require 未回应）的 sentenceId 为空，证据在 triggerSentenceId 与 note 里。
 */
public record ViolationResponse(
    Long id,
    Long ruleVersionId,
    String ruleName,
    String matchType,
    String scope,
    Long sentenceId,
    Long triggerSentenceId,
    List<Long> evidenceSentenceIds,
    List<SentenceRange> ranges,
    Integer deduct,
    String note,
    /** 该规则当前绑定的法律条款（V13）：绑定实时生效，非发布时快照 */
    List<LegalClauseResponse> legalClauses
) {

    /** 高亮区间：句子 id + 原文 UTF-16 起止（左闭右开） */
    public record SentenceRange(Long sentenceId, int start, int end) {
    }
}
