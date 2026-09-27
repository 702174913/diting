package com.diting.common;

import java.util.Locale;
import java.util.Set;

/**
 * 密码强度基线（后台账号与初始化账号共用）。
 * 目的是拦住默认弱密码与“用户名即密码”，不追求复杂度军备竞赛。
 */
public final class PasswordPolicy {

    /** 后台密码最小长度 */
    public static final int MIN_LENGTH = 10;
    /** C 端最小长度（移动端输入成本；强度表与字母数字要求与后台一致） */
    public static final int MIN_USER_LENGTH = 8;

    private static final Set<String> WEAK = Set.of(
        "12345678", "123456789", "1234567890", "password", "password123",
        "admin123", "admin123456", "qwerty123", "11111111", "88888888",
        "diting123", "abc123456");

    private PasswordPolicy() {
    }

    /** 不满足时抛 422，message 直接可展示给操作者 */
    public static void assertStrong(String rawPassword, String username) {
        assertStrongWithMin(rawPassword, username, MIN_LENGTH);
    }

    /**
     * C 端注册用：长度门槛 8 位，其余与后台同一标准（弱密码表、含用户名、字母+数字）。
     * 两套标准只差长度一行，不各写一份弱密码表——否则迟早走样（P1-3 修复）。
     */
    public static void assertStrongForUser(String rawPassword, String username) {
        assertStrongWithMin(rawPassword, username, MIN_USER_LENGTH);
    }

    private static void assertStrongWithMin(String rawPassword, String username, int minLength) {
        if (rawPassword == null || rawPassword.length() < minLength) {
            throw new BizException(ResultCode.UNPROCESSABLE, "密码至少 " + minLength + " 位");
        }
        String lower = rawPassword.toLowerCase(Locale.ROOT);
        if (WEAK.contains(lower)) {
            throw new BizException(ResultCode.UNPROCESSABLE, "密码过于简单，请更换");
        }
        if (username != null && !username.isBlank()
            && (lower.contains(username.toLowerCase(Locale.ROOT)))) {
            throw new BizException(ResultCode.UNPROCESSABLE, "密码不能包含用户名");
        }
        boolean hasLetter = lower.chars().anyMatch(Character::isLetter);
        boolean hasDigit = lower.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ResultCode.UNPROCESSABLE, "密码需同时包含字母与数字");
        }
    }
}
