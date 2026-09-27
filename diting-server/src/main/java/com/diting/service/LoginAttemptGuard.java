package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小登录失败保护：同一「身份域 + 账号 + 客户端 IP」在窗口内失败次数超限即拒绝。
 * 进程内计数（单实例足够）；多实例部署时应换成共享存储，这一点记录在 S02 交付说明里。
 */
@Component
public class LoginAttemptGuard {

    /** 窗口内允许的失败次数 */
    public static final int MAX_FAILURES = 5;
    /** 统计窗口与锁定时间 */
    public static final Duration WINDOW = Duration.ofMinutes(10);
    /** 防止异常流量把计数表撑爆 */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    /** 仅测试使用：注入可控时钟 */
    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    public void assertAllowed(String scope, String key) {
        Deque<Instant> window = failures.get(composeKey(scope, key));
        if (window == null) {
            return;
        }
        synchronized (window) {
            prune(window);
            if (window.size() >= MAX_FAILURES) {
                throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "失败次数过多，请 " + WINDOW.toMinutes() + " 分钟后再试");
            }
        }
    }

    public void recordFailure(String scope, String key) {
        if (failures.size() > MAX_KEYS) {
            failures.clear();
        }
        Deque<Instant> window = failures.computeIfAbsent(composeKey(scope, key),
            k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window);
            window.addLast(clock.instant());
        }
    }

    public void clear(String scope, String key) {
        failures.remove(composeKey(scope, key));
    }

    /** 当前窗口内失败次数（测试与排查用） */
    public int failureCount(String scope, String key) {
        Deque<Instant> window = failures.get(composeKey(scope, key));
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            prune(window);
            return window.size();
        }
    }

    private void prune(Deque<Instant> window) {
        Instant deadline = clock.instant().minus(WINDOW);
        while (!window.isEmpty() && window.peekFirst().isBefore(deadline)) {
            window.pollFirst();
        }
    }

    private static String composeKey(String scope, String key) {
        return scope + '|' + key;
    }
}
