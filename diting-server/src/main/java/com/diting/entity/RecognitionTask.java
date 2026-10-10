package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recognition_tasks")
public class RecognitionTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 上传会话 ID（upload_sessions.id），新流程的可信来源 */
    private Long uploadId;

    /** AUDIO/IMAGE/MIXED：决定管线条走转写、OCR 还是两者（S16） */
    private String mediaKind;

    /** 客户端幂等键，历史数据为空；uk(user_id, idempotency_key) 防重复扣份数 */
    private String idempotencyKey;

    /** 通义听悟任务号（物理列 task_id；API 层对外叫 remoteTaskId，不另加第二份远端 ID） */
    private String taskId;

    /** OSS 对象 key（历史字段，新流程见 uploadId） */
    private String ossObject;

    /**
     * 任务状态。
     * 主线：UPLOADED→QUEUED→SUBMITTING→TRANSCRIBING→PARSING→ANALYZING→COMPLETED；
     * WAITING_ROLE 仅保留给历史任务自动接续。失败分支 FAILED / INVALID，删除 DELETING/DELETED；
     * 历史值 PENDING/ONGOING 由 S06 状态机按迁移规则转换。
     */
    private String status;

    private Integer durationSec;

    /** 说话人映射（哪边是催收方）是否已确认，POC 阶段重点字段 */
    private Boolean speakerMappingConfirmed;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    /** 乐观锁版本，作业租约与 CAS 使用 */
    private Long version;

    /** 已尝试次数 */
    private Integer attemptCount;

    /** 结果下载/解析的失败次数；不包含正常远端轮询。 */
    private Integer parseFailures;

    /** 作业租约到期时间 */
    private LocalDateTime leaseUntil;

    /** 下次可领取时间（退避） */
    private LocalDateTime nextAttemptAt;

    /** 安全错误码，不存敏感响应 */
    private String errorCode;

    /** 原件保留截止（上传时间 + 30 天） */
    private LocalDateTime expiresAt;

    /** 原件是否已清理 */
    private Boolean audioPurged;

    /** 墓碑时间，非空即不可再访问 */
    private LocalDateTime deletedAt;
}
