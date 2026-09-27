package com.diting.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S06 状态机：合法迁移、终态判定、旧值映射 */
class TaskStatusTest {

    @Test
    @DisplayName("主链路与分支的合法迁移")
    void legalTransitions() {
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.SUBMITTING)).isTrue();
        assertThat(TaskStatus.SUBMITTING.canTransitionTo(TaskStatus.TRANSCRIBING)).isTrue();
        assertThat(TaskStatus.TRANSCRIBING.canTransitionTo(TaskStatus.PARSING)).isTrue();
        assertThat(TaskStatus.PARSING.canTransitionTo(TaskStatus.ANALYZING)).isTrue();
        // 历史 WAITING_ROLE 可由后台自动推进
        assertThat(TaskStatus.WAITING_ROLE.canTransitionTo(TaskStatus.ANALYZING)).isTrue();
        assertThat(TaskStatus.ANALYZING.canTransitionTo(TaskStatus.COMPLETED)).isTrue();
        assertThat(TaskStatus.TRANSCRIBING.canTransitionTo(TaskStatus.INVALID)).isTrue();
        assertThat(TaskStatus.DELETING.canTransitionTo(TaskStatus.DELETED)).isTrue();
    }

    @Test
    @DisplayName("非法迁移被拒绝：不能跳级完成、不能从终态复活")
    void illegalTransitions() {
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.COMPLETED)).isFalse();
        assertThat(TaskStatus.TRANSCRIBING.canTransitionTo(TaskStatus.COMPLETED)).isFalse();
        assertThat(TaskStatus.PARSING.canTransitionTo(TaskStatus.COMPLETED)).isFalse();
        assertThat(TaskStatus.DELETED.canTransitionTo(TaskStatus.QUEUED)).isFalse();
        assertThat(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.TRANSCRIBING)).isFalse();
        assertThat(TaskStatus.INVALID.canTransitionTo(TaskStatus.ANALYZING)).isFalse();
        // 重算是从 COMPLETED 回到 ANALYZING 新建版本，而不是直接改分
        assertThat(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.ANALYZING)).isTrue();
        // 失败可有限重试
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.QUEUED)).isTrue();
    }

    @Test
    @DisplayName("终态与运行中判定")
    void flags() {
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.INVALID.isTerminal()).isTrue();
        assertThat(TaskStatus.DELETED.isTerminal()).isTrue();
        assertThat(TaskStatus.WAITING_ROLE.isTerminal()).isFalse();
        assertThat(TaskStatus.WAITING_ROLE.isRunning()).isTrue();
        assertThat(TaskStatus.DELETING.isRunning()).isFalse();
        assertThat(TaskStatus.QUEUED.needsWorker()).isTrue();
        assertThat(TaskStatus.WAITING_ROLE.needsWorker()).isTrue();
    }

    @Test
    @DisplayName("旧值映射：PENDING→QUEUED、ONGOING→TRANSCRIBING，非法值返回 null")
    void legacyMapping() {
        assertThat(TaskStatus.fromLegacy("PENDING")).isEqualTo(TaskStatus.QUEUED);
        assertThat(TaskStatus.fromLegacy("ongoing")).isEqualTo(TaskStatus.TRANSCRIBING);
        assertThat(TaskStatus.fromLegacy("COMPLETED")).isEqualTo(TaskStatus.COMPLETED);
        assertThat(TaskStatus.fromLegacy("WHATEVER")).isNull();
        assertThat(TaskStatus.fromLegacy(null)).isNull();
    }
}
