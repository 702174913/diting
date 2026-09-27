package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sentences")
public class Sentence {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /**
     * 句序，task 内唯一（uk(task_id, sequence)）。
     * 按（start_ms, id）确定性生成；报告与高亮都按它排序，不依赖自增 id。
     */
    private Integer sequence;

    /** 图片 OCR 来源（task_assets.id）；音频句子为空（S16） */
    private Long sourceAssetId;

    /** 听悟声学聚类出的说话人标签，不等于业务角色 */
    private Integer speakerId;

    private Long startMs;

    private Long endMs;

    private String text;

    /** 词级时间戳明细（JSON 文本），可为空 */
    private String words;

    /** 语速（字/分钟），由词级时间戳自行计算 */
    private Integer speechSpeed;

    /** 是否命中判读规则（要留意的话） */
    private Boolean flagged;

    /** 依据（规范条文引用） */
    private String ruleRef;
}
