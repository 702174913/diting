package com.diting.dto;

import com.diting.entity.RecognitionTask;

public record TaskItemResponse(
    Long id,
    String taskId,
    String status,
    Integer durationSec,
    String createdAt,
    /** 命中规则的句子数：「找到 N 处要留意的话」 */
    Integer flaggedCount,
    /** 转写出的句子总数：「听完的句子」 */
    Integer sentenceCount,
    Integer violationCount,
    boolean reportPublished
) {
    public static TaskItemResponse of(RecognitionTask task, int flaggedCount, int sentenceCount) {
        return of(task, flaggedCount, sentenceCount, 0, false);
    }

    public static TaskItemResponse of(RecognitionTask task, int flaggedCount, int sentenceCount,
                                      int violationCount, boolean reportPublished) {
        return new TaskItemResponse(
            task.getId(),
            task.getTaskId(),
            task.getStatus(),
            task.getDurationSec(),
            task.getCreatedAt() == null ? "" : task.getCreatedAt().toString(),
            flaggedCount,
            sentenceCount,
            violationCount,
            reportPublished
        );
    }
}
