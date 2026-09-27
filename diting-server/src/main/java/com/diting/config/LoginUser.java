package com.diting.config;

import com.diting.common.AdminRole;
import com.diting.common.LoginDomain;

/**
 * 当前登录身份（请求线程内有效）。
 * C 端身份 role 为 null；后台身份 role 来自 admin_users，每次请求重新查库判定。
 */
public record LoginUser(Long id, LoginDomain domain, AdminRole role) {

    public static LoginUser user(Long id) {
        return new LoginUser(id, LoginDomain.USER, null);
    }

    public static LoginUser admin(Long id, AdminRole role) {
        return new LoginUser(id, LoginDomain.ADMIN, role);
    }
}
