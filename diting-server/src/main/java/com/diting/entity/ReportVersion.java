package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本化报告（开发文档 §6.3）。
 * uk(task_id, version) 保证同一任务同一版本只发布一次；
 * riskScore / complianceScore 在发布时必填，发布前为空表示尚未出分，不能伪造成 0。
 */
@Data
@TableName("report_versions")
public class ReportVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 报告版本，从 1 起递增 */
    private Long version;

    /** rule_sets.version */
    private Long ruleSetVersion;

    /** speaker_mappings.revision */
    private Long mappingRevision;

    /** min(100, 去重扣分总和) */
    private Integer riskScore;

    /** 100 - riskScore */
    private Integer complianceScore;

    /** 本次判读使用的阈值快照 */
    private Integer thresholdSnapshot;

    /** app_config.version 快照 */
    private Long configVersion;

    /** ANALYZING / PUBLISHED / FAILED */
    private String status;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;
}
