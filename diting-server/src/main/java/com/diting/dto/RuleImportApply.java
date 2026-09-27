package com.diting.dto;

import java.util.List;

/** 批量导入落库结果：成功的条数与失败行明细 */
public record RuleImportApply(
    int created,
    int updated,
    int failed,
    List<RuleImportRow> failures
) {
}
