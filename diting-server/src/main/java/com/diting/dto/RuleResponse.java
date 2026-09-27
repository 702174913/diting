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
    LocalDateTime updatedAt
) {
}
