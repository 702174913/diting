package com.diting.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 规则绑定法律条款：替换式提交，空数组表示全部解绑 */
public record RuleLegalClauseBindRequest(
    @NotNull(message = "clauseIds 不能为空（解绑传空数组）") List<Long> clauseIds
) {
}
