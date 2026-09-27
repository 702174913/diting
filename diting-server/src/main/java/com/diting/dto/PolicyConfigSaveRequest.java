package com.diting.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 保存三项策略配置。
 * expectedVersion 是乐观锁：与库中当前版本不一致返回 409，避免把别人刚改的配置覆盖掉。
 * 取值范围由 ConfigService 统一校验（份数≥0、阈值 0-100、时长>0）。
 */
public record PolicyConfigSaveRequest(
    @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
    @NotNull(message = "每日免费份数不能为空") Integer dailyFreeQuota,
    @NotNull(message = "风险阈值不能为空") Integer riskThreshold,
    @NotNull(message = "最长录音时长不能为空") Integer maxRecordingMinutes
) {
}
