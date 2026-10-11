package com.diting.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 规则详情：附带当前版本号，修改时必须回传 expectedVersion */
public record RuleResponse(
    Long ruleId,
    String code,
    Integer version,
    String groupName,
    String name,
    String description,
    String matchType,
    String roleScope,
    String triggerRole,
    List<String> keywords,
    List<String> expectedKeywords,
    Integer windowSize,
    Integer deduct,
    boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    /**
     * 绑定的法律条款（V13）。绑定按规则（非版本）存储、实时生效：
     * 仅 get/list/create/update 返回当前绑定；版本历史接口恒为空列表，
     * 避免把「当前绑定」误读为「历史版本快照」。
     */
    List<LegalClauseResponse> legalClauses
) {
}
