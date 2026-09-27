package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（开发文档 §6.4）。
 * 只记录元数据：动作、目标、结果、IP；禁止写入秘密、签名 URL 与对话原文。
 * 经手动作只证明“授权链接已签发”，不代表用户已听完。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** USER / ADMIN / SYSTEM */
    private String actorDomain;

    private Long actorId;

    /** 动作码 */
    private String action;

    private String targetType;

    private String targetId;

    /** SUCCESS / DENIED / FAILED */
    private String result;

    /** 附加元数据（JSON 文本），不含敏感内容 */
    private String detail;

    private String ip;

    private LocalDateTime createdAt;
}
