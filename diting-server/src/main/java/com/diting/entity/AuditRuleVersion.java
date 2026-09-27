package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则版本快照（开发文档 §6.3）。
 * keywords / expectedKeywords 为 JSON 数组文本；matchType 取 hit / require / must-have；
 * require 需要 triggerRole + windowSize 明确触发方与按目标角色发言条数的窗口。
 */
@Data
@TableName("audit_rule_versions")
public class AuditRuleVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;

    private Integer version;

    /** 模型组/规则分组名 */
    private String groupName;

    private String name;

    /** 平台规则说明，不写法律结论 */
    private String description;

    /** hit / require / must-have */
    private String matchType;

    /** 约束角色范围 */
    private String roleScope;

    /** require 的触发方角色，其他类型可空 */
    private String triggerRole;

    /** 触发词/关键词数组（JSON 文本） */
    private String keywords;

    /** require 的应答词数组（JSON 文本，非 require 存空数组） */
    private String expectedKeywords;

    /** require 窗口：后续目标角色发言条数 */
    private Integer windowSize;

    /** 扣分 0—100 */
    private Integer deduct;

    private LocalDateTime createdAt;
}
