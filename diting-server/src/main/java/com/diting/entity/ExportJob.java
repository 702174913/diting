package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PDF / ZIP 导出作业（开发文档 §6.4）。
 * ownerDomain 区分 USER 与 ADMIN 两个身份域，作业归属按 (owner_domain, owner_id) 校验，
 * 后台 token 不能调用 C 端导出路由；含音频的 ZIP 有效期不得超过原件保留截止时间。
 */
@Data
@TableName("export_jobs")
public class ExportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    /** USER / ADMIN */
    private String ownerDomain;

    private Long reportVersionId;

    /** 是否打包原始音频 */
    private Boolean withAudio;

    /** QUEUED / RUNNING / COMPLETED / FAILED */
    private String status;

    /** 产物对象 key，发布前必填 */
    private String artifactKey;

    /** 产物有效期 */
    private LocalDateTime expiresAt;

    private Integer attemptCount;

    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;

    private String errorCode;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
