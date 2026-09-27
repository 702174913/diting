package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 额度流水（开发文档 §6.4）。
 * uk(task_id) 保证一个任务只扣一次份数；state 在 RESERVED / CONSUMED / RELEASED 之间
 * 用 CAS 转换，重复退款会被唯一键拦下。
 */
@Data
@TableName("quota_ledger")
public class QuotaLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long taskId;

    private LocalDate businessDate;

    /** RESERVED / CONSUMED / RELEASED */
    private String state;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
