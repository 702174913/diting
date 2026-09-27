package com.diting.service.rules;

import java.util.List;

/**
 * 判读引擎的输入与输出（纯数据结构，不含任何持久化）。
 * 高亮用**原文 UTF-16 偏移、左闭右开**：[start, end) 可直接被 JS 的 slice 使用。
 */
public final class RuleModels {

    private RuleModels() {
    }

    /** 规则定义（来自规则版本快照，判读时只读） */
    public record RuleDefinition(
        Long ruleId,
        Long ruleVersionId,
        String code,
        String groupName,
        String name,
        String description,
        MatchType matchType,
        String roleScope,
        String triggerRole,
        List<String> keywords,
        List<String> expectedKeywords,
        Integer windowSize,
        int deduct
    ) {

        public boolean roleDependent() {
            return roleScope != null && !roleScope.isBlank() && !"ANY".equalsIgnoreCase(roleScope.trim());
        }
    }

    /** 句子视图：id、序号、说话人标签与原文 */
    public record SentenceView(Long id, int sequence, Integer speakerId, String text) {
    }

    /** 高亮区间：原文 UTF-16 偏移，左闭右开 */
    public record HighlightRange(Long sentenceId, int start, int end) {
    }

    /**
     * 命中或缺失证据。
     * 通话级缺失（must-have / require 未回应）没有具体句：sentenceId 为 null，
     * 证据放在 triggerSentenceId 或 note 里，绝不用首句冒充。
     */
    public record ViolationItem(
        Long ruleId,
        Long ruleVersionId,
        String ruleCode,
        String ruleName,
        MatchType matchType,
        String scope,
        Long sentenceId,
        Long triggerSentenceId,
        List<Long> evidenceSentenceIds,
        List<HighlightRange> ranges,
        int deduct,
        String note
    ) {
    }

    /** 因前置条件不足而被跳过的规则（例如角色未确认） */
    public record SkippedRule(Long ruleId, String ruleName, String reason) {
    }

    /** 判读结果：证据 + 分数 + 跳过说明 */
    public record AnalysisResult(
        List<ViolationItem> violations,
        int riskScore,
        int complianceScore,
        List<SkippedRule> skipped,
        List<String> notes
    ) {

        public boolean hasViolations() {
            return !violations.isEmpty();
        }
    }
}
