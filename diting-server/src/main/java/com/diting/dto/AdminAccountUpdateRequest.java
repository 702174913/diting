package com.diting.dto;

import jakarta.validation.constraints.Size;

/** 修改后台账号：字段都可选，但至少要传一个，否则返回 422 */
public record AdminAccountUpdateRequest(
    @Size(max = 64, message = "昵称最长 64 位") String nickname,
    String role,
    Boolean enabled,
    @Size(max = 72, message = "密码最长 72 位") String password
) {

    public boolean isEmpty() {
        return (nickname == null || nickname.isBlank())
            && (role == null || role.isBlank())
            && enabled == null
            && (password == null || password.isBlank());
    }
}
