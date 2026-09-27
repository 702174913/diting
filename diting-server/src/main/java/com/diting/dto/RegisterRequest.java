package com.diting.dto;

import com.diting.common.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * C 端注册。长度门槛 8 位（移动端输入成本），但弱密码表与“密码含用户名”
 * 与后台共用同一份 PasswordPolicy——C 端账号同样能看通话原文，
 * 弱密码撞库风险不比后台低（P1-3 修复）。
 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度需为 4-32 位") String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需为 8-64 位") String password,

    /**
     * 注册时必须勾选同意 4 份用户协议（S19）。
     * agreed=true 且 seenVersions 与服务端当前版本一致才放行——
     * 版本对不上说明 admin 刚更新过协议，让用户重新看一遍再勾。
     */
    @NotNull(message = "请先阅读并同意用户协议") Boolean agreed,

    Map<String, Integer> seenVersions
) {
    /** Bean Validation 只做长度；强度由 AuthService 调 PasswordPolicy 统一校验 */
    public void assertPasswordStrong() {
        PasswordPolicy.assertStrongForUser(password, username);
    }
}
