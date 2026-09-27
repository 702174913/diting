package com.diting.dto;

import java.util.List;

/**
 * 批量导入预检报告：只看不写。
 * 确认后才走落库接口，避免一行写错直接影响下一次判读。
 */
public record RuleImportPrecheck(
    int total,
    int createCount,
    int updateCount,
    int errorCount,
    List<RuleImportRow> rows
) {
}
