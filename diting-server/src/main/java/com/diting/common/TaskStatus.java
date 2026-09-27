package com.diting.common;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机（开发文档 §5.2）。
 *
 * 主线：UPLOADED → QUEUED → SUBMITTING → TRANSCRIBING → PARSING → ANALYZING → COMPLETED
 * WAITING_ROLE 是历史任务兼容态，升级后由后台自动分析，不再等待用户确认。
 * 分支：FAILED / INVALID；删除：DELETING → DELETED（优先于其他状态）。
 *
 * 约定：
 * - 转换只允许走 {@link #canTransitionTo} 列出的合法路径，非法转换必须被拒绝而不是“尽量改”；
 * - COMPLETED 只能由 ANALYZING 进入，且调用方必须已事务发布报告版本（否则会出现“先完成、后无结果”）；
 * - 旧值 PENDING/ONGOING 由迁移脚本与 {@link #fromLegacy} 统一映射，不允许前后端各用一套。
 */
public enum TaskStatus {

    UPLOADED,
    QUEUED,
    SUBMITTING,
    TRANSCRIBING,
    PARSING,
    WAITING_ROLE,
    ANALYZING,
    COMPLETED,
    FAILED,
    INVALID,
    DELETING,
    DELETED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.ofEntries(
        Map.entry(UPLOADED, EnumSet.of(QUEUED, FAILED, DELETING)),
        Map.entry(QUEUED, EnumSet.of(SUBMITTING, FAILED, DELETING)),
        Map.entry(SUBMITTING, EnumSet.of(TRANSCRIBING, QUEUED, FAILED, DELETING)),
        Map.entry(TRANSCRIBING, EnumSet.of(PARSING, FAILED, INVALID, DELETING)),
        Map.entry(PARSING, EnumSet.of(WAITING_ROLE, ANALYZING, INVALID, FAILED, DELETING)),
        Map.entry(WAITING_ROLE, EnumSet.of(ANALYZING, DELETING)),
        Map.entry(ANALYZING, EnumSet.of(COMPLETED, FAILED, DELETING)),
        Map.entry(COMPLETED, EnumSet.of(ANALYZING, DELETING)),
        Map.entry(FAILED, EnumSet.of(QUEUED, DELETING)),
        Map.entry(INVALID, EnumSet.of(DELETING)),
        Map.entry(DELETING, EnumSet.of(DELETED)),
        Map.entry(DELETED, EnumSet.noneOf(TaskStatus.class))
    );

    public boolean canTransitionTo(TaskStatus next) {
        return next != null && ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    /** 终态：不再有后台推进 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == INVALID || this == DELETED;
    }

    /** 仍在处理中：前端按“处理中”展示 */
    public boolean isRunning() {
        return !isTerminal() && this != DELETING;
    }

    /** 需要后台作业推进的状态；历史 WAITING_ROLE 也要自动完成 */
    public boolean needsWorker() {
        return this == QUEUED || this == SUBMITTING || this == TRANSCRIBING
            || this == PARSING || this == WAITING_ROLE;
    }

    public static TaskStatus of(String value) {
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

    /** 旧值映射：PENDING → QUEUED，ONGOING → TRANSCRIBING（与迁移脚本保持一致） */
    public static TaskStatus fromLegacy(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> QUEUED;
            case "ONGOING" -> TRANSCRIBING;
            default -> of(value);
        };
    }
}
