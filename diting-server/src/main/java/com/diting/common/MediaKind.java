package com.diting.common;

import java.util.Locale;

/**
 * 媒体类型（S16）。
 *
 * - {@link #AUDIO}：只有录音，走听悟转写；
 * - {@link #IMAGE}：只有图片，走 OCR，且**跳过角色确认**（没有说话人可确认）；
 * - {@link #MIXED}：录音 + 图片，先转写再 OCR，句子合并进同一份报告。
 *
 * 上传会话只用 AUDIO/IMAGE（单次上传就是一个对象）；任务用三者。
 */
public enum MediaKind {

    AUDIO,
    IMAGE,
    MIXED;

    public static MediaKind of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 任务是否含音频（决定要不要走听悟与角色确认） */
    public static boolean hasAudio(String value) {
        MediaKind kind = of(value);
        return kind == AUDIO || kind == MIXED;
    }

    /** 任务是否含图片（决定要不要走 OCR） */
    public static boolean hasImage(String value) {
        MediaKind kind = of(value);
        return kind == IMAGE || kind == MIXED;
    }

    /** 由「有音频/有图片」推导任务类型 */
    public static MediaKind derive(boolean audio, boolean image) {
        if (audio && image) {
            return MIXED;
        }
        return image ? IMAGE : AUDIO;
    }
}
