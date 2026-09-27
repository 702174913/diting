package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 判读规则主表（开发文档 §6.3）。
 * 停用只影响未来规则集合，历史版本与历史报告引用都不删除。
 */
@Data
@TableName("audit_rules")
public class AuditRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则业务编码 */
    private String code;

    /** 当前生效版本号（对应 audit_rule_versions.version） */
    private Integer currentVersion;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
