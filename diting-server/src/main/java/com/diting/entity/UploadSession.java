package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OSS 预签名上传会话（开发文档 §6.2）。
 * 对象 key 由服务端生成并绑定 owner；提交任务以 uploadId 为准，不接受任意 objectKey。
 */
@Data
@TableName("upload_sessions")
public class UploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户，取自 token */
    private Long userId;

    /** OSS 对象 key，不含用户可控路径 */
    private String objectKey;

    /** SIGNED / VERIFIED / CONSUMED */
    private String status;

    /** AUDIO/IMAGE：签发时按文件名判定，complete 时按文件头复核（S16） */
    private String mediaKind;

    /** 客户端原始文件名，仅用于展示 */
    private String filename;

    /** 对象实际长度（complete 阶段由服务端写入） */
    private Long sizeBytes;

    /** 服务端检测的真实时长（毫秒） */
    private Long durationMs;

    /** 服务端检测的真实格式，不信任扩展名与客户端声明 */
    private String mediaFormat;

    /** 对象内容哈希 */
    private String contentHash;

    /** 用户同意的条款版本 */
    private String consentVersion;

    private LocalDateTime consentedAt;

    /** 签名有效期 */
    private LocalDateTime signedUntil;

    /** 孤立对象清理时间 */
    private LocalDateTime cleanupAfter;

    private LocalDateTime createdAt;
}
