package com.diting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增/编辑法律条款。
 * enabled 为空时：新建默认启用，编辑保持原状态。
 */
public record LegalClauseSaveRequest(
    @NotBlank(message = "法规名称不能为空")
    @Size(max = 200, message = "法规名称最长 200 位") String lawName,

    @NotBlank(message = "条款号不能为空")
    @Size(max = 64, message = "条款号最长 64 位") String articleNo,

    @NotBlank(message = "条款原文不能为空") String content,

    @Size(max = 500, message = "备注最长 500 位") String remark,

    Boolean enabled
) {
}
