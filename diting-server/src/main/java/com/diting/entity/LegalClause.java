package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 法律条款库（V13）：法规名称 + 条款号 + 原文，由后台维护。
 * 停用不物理删除：已有规则绑定与历史报告的引用不因停用而消失。
 */
@Data
@TableName("legal_clauses")
public class LegalClause {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 法律/规范名称，如：个人信息保护法 */
    private String lawName;

    /** 条款号，如：第十三条 */
    private String articleNo;

    /** 条款原文（报告引用展示） */
    private String content;

    /** 备注（内部说明，不进报告） */
    private String remark;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
