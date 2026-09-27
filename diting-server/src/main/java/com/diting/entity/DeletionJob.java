package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 删除/清理作业（开发文档 §6.2）。
 * uk(target_type, target_id) 保证同一目标只有一个清理作业，重复请求幂等唤醒同一作业；
 * 只保留清理必需数据，不存对话原文与签名 URL。
 */
@Data
@TableName("deletion_jobs")
public class DeletionJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起删除的用户 */
    private Long userId;

    /** TASK / ERASE 等删除目标类型 */
    private String targetType;

    /** 目标 ID（TASK 时为 recognition_tasks.id） */
    private Long targetId;

    /** QUEUED / RUNNING / RETRY_WAIT / COMPLETED / FAILED */
    private String status;

    /** 待清理对象清单（JSON 字符串），仅保留清理必需数据 */
    private String remainingObjects;

    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    /** 当前清理工作者专属令牌；用户重新排期或租约被抢后旧令牌失效 */
    private String leaseToken;

    /** 安全错误码，不含原文 */
    private String errorCode;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
