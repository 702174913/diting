package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则↔法律条款绑定（V13）。
 * 按 audit_rules.id（非版本）存储：修改绑定实时生效，不生成规则新版本。
 */
@Data
@TableName("rule_legal_clauses")
public class RuleLegalClause {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** audit_rules.id */
    private Long ruleId;

    /** legal_clauses.id */
    private Long clauseId;

    /** 展示顺序（绑定时的提交顺序） */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
