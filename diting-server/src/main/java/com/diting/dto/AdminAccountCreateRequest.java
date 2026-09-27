package com.diting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建后台账号。密码强度由服务端 PasswordPolicy 校验（长度≥10、含字母与数字、不含用户名），
 * 这里只做基本存在性与长度约束。
 */
public record AdminAccountCreateRequest(
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名最长 64 位") String username,

    @NotBlank(message = "密码不能为空")
    @Size(max = 72, message = "密码最长 72 位") String password,

    @Size(max = 64, message = "昵称最长 64 位") String nickname,

    @NotBlank(message = "角色不能为空") String role
) {
}
