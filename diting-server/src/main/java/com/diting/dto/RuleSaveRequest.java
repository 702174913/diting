package com.diting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增/修改规则（开发文档 §4.2 后台规则库、§8 规则语义）。
 * 字段级校验在这里；业务校验（类型/角色/窗口/扣分与关键词组合）在 RuleService，
 * 保证「require 必须有触发角色与窗口」这类规则不会被绕过。
 */
public record RuleSaveRequest(
    @NotBlank(message = "规则编码不能为空")
    @Size(max = 64, message = "规则编码最长 64 位") String code,

    @Size(max = 100, message = "分组名最长 100 位") String groupName,

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 200, message = "规则名称最长 200 位") String name,

    @NotBlank(message = "规则说明不能为空") String description,

    @NotBlank(message = "规则类型不能为空") String matchType,

    String roleScope,

    String triggerRole,

    List<String> keywords,

    List<String> expectedKeywords,

    Integer windowSize,

    Integer deduct
) {
}
