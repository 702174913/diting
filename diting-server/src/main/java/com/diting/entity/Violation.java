package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 规则命中证据（开发文档 §6.3）。
 * uk(report_version_id, rule_version_id, evidence_key) 保证同规则同证据只记一次；
 * 通话级缺失（must-have）允许 sentenceId 为空，不把首句当伪证据。
 * ranges 为 [{sentenceId,start,end}] 的 JSON 文本，使用原文 UTF-16 偏移、左闭右开。
 */
@Data
@TableName("violations")
public class Violation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportVersionId;

    private Long ruleVersionId;

    /** 命中句；通话级缺失时为空 */
    private Long sentenceId;

    /** require 的触发句 */
    private Long triggerSentenceId;

    /** 证据句 ID 数组（JSON 文本） */
    private String evidenceSentenceIds;

    /** 高亮区间数组（JSON 文本） */
    private String ranges;

    /** 本项扣分 0—100 */
    private Integer deduct;

    /** 说明，引用规则版本，不做法律保证 */
    private String note;

    /** 去重键 */
    private String evidenceKey;
}
