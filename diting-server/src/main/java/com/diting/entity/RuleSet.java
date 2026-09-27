package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则集合快照（开发文档 §6.3）。
 * 固定“一次分析用了哪些规则版本”，使历史报告可复算；不是独立的模型管理功能。
 */
@Data
@TableName("rule_sets")
public class RuleSet {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则集合版本 */
    private Long version;

    /** 本次分析启用的 audit_rule_versions.id 列表（JSON 数组文本） */
    private String ruleVersionIds;

    private LocalDateTime createdAt;
}
