package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务资产（S16）：一条任务下的音频与图片。
 *
 * - 一条任务最多 1 个 AUDIO（sequence 固定 0）+ 6 个 IMAGE（sequence 1..6，按用户选择顺序）；
 * - `ocr_status`：音频恒为 SKIPPED；图片走 PENDING → RUNNING → DONE/EMPTY/FAILED；
 * - `ocr_text` 保留原始识别文本（审计与「这句话来自哪张图」的回溯）；
 *   句子本身落在 sentences 表，并通过 sentences.source_asset_id 指回这里。
 */
@Data
@TableName("task_assets")
public class TaskAsset {

    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_IMAGE = "IMAGE";

    public static final String OCR_SKIPPED = "SKIPPED";
    public static final String OCR_PENDING = "PENDING";
    public static final String OCR_RUNNING = "RUNNING";
    public static final String OCR_DONE = "DONE";
    public static final String OCR_EMPTY = "EMPTY";
    public static final String OCR_FAILED = "FAILED";
    /** 重试次数用尽或云端明确拒绝（重试无用）：不再自动重试，等人工处理 */
    public static final String OCR_EXHAUSTED = "EXHAUSTED";

    /** 失败原因标记：网络中断/超时等不确定失败（云端错误码则原样落库，便于运维定位） */
    public static final String OCR_ERROR_UNCERTAIN = "OCR_UNCERTAIN";
    /** 失败原因标记：对象存储里读不到原件 */
    public static final String OCR_ERROR_OBJECT_MISSING = "OBJECT_MISSING";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** upload_sessions.id；提交时该会话被消费 */
    private Long uploadId;

    private String kind;

    /** 任务内顺序：音频 0，图片 1..6 */
    private Integer sequence;

    private String objectKey;

    private String filename;

    private String mediaFormat;

    private Long sizeBytes;

    private String ocrStatus;

    /** 每次领取（含崩溃恢复）都递增，避免永久失败无限付费重试。 */
    private Integer ocrAttempts;
    private String ocrLeaseToken;
    private LocalDateTime ocrLeaseUntil;

    /** OCR 汇总文本（content）；音频为空 */
    private String ocrText;

    /** 失败原因（不含原文与凭据；云端错误码如 ocrServiceNotOpen / noPermission 原样保留） */
    private String ocrError;

    private LocalDateTime createdAt;

    private LocalDateTime ocrDoneAt;

    public boolean isImage() {
        return KIND_IMAGE.equals(kind);
    }
}
