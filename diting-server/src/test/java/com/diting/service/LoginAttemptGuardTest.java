package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 登录失败保护：窗口内失败超限后拒绝，成功登录清零，窗口滑过后恢复 */
class LoginAttemptGuardTest {

    private static final Instant START = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    @DisplayName("失败达到上限后拒绝后续尝试（429）")
    void blocksAfterMaxFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        guard.useClock(Clock.fixed(START, ZoneOffset.UTC));
        String key = "alice@10.0.0.1";

        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.assertAllowed("ADMIN_LOGIN", key);
            guard.recordFailure("ADMIN_LOGIN", key);
        }

        assertThatThrownBy(() -> guard.assertAllowed("ADMIN_LOGIN", key))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("成功登录后计数清零")
    void clearOnSuccess() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        guard.useClock(Clock.fixed(START, ZoneOffset.UTC));
        guard.recordFailure("ADMIN_LOGIN", "bob@10.0.0.2");
        guard.recordFailure("ADMIN_LOGIN", "bob@10.0.0.2");
        assertThat(guard.failureCount("ADMIN_LOGIN", "bob@10.0.0.2")).isEqualTo(2);

        guard.clear("ADMIN_LOGIN", "bob@10.0.0.2");

        assertThat(guard.failureCount("ADMIN_LOGIN", "bob@10.0.0.2")).isZero();
        guard.assertAllowed("ADMIN_LOGIN", "bob@10.0.0.2");
    }

    @Test
    @DisplayName("窗口滑过后恢复，且不同身份域/账号/来源互不干扰")
    void windowSlidesAndKeysAreIsolated() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        guard.useClock(Clock.fixed(START, ZoneOffset.UTC));
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.recordFailure("ADMIN_LOGIN", "carol@10.0.0.3");
        }

        // 其它维度不受影响
        guard.assertAllowed("ADMIN_LOGIN", "carol@10.0.0.4");
        guard.assertAllowed("ADMIN_LOGIN", "dave@10.0.0.3");
        guard.assertAllowed("USER_LOGIN", "carol@10.0.0.3");

        // 窗口滑过 10 分钟后放行
        guard.useClock(Clock.fixed(START.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        assertThat(guard.failureCount("ADMIN_LOGIN", "carol@10.0.0.3")).isZero();
        guard.assertAllowed("ADMIN_LOGIN", "carol@10.0.0.3");
    }
}
