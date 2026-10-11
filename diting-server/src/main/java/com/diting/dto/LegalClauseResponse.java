package com.diting.dto;

import java.time.LocalDateTime;

/** 法律条款详情（规则绑定与报告依据展示共用同一形态） */
public record LegalClauseResponse(
    Long id,
    String lawName,
    String articleNo,
    String content,
    String remark,
    boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
