package com.diting.config;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.common.LoginDomain;
import com.diting.common.ResultCode;

/** 当前登录身份上下文（由对应域的鉴权拦截器写入，请求线程内有效） */
public final class LoginUserHolder {

    private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();

    private LoginUserHolder() {
    }

    public static void set(LoginUser user) {
        CURRENT.set(user);
    }

    /** 未登录直接抛 401，调用方无需判空 */
    public static LoginUser get() {
        LoginUser user = CURRENT.get();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long getUserId() {
        return get().id();
    }

    public static LoginDomain getDomain() {
        return get().domain();
    }

    /** 当前后台角色的最小权限读取，越权抛 403 */
    public static AdminRole requireRole(AdminRole... allowed) {
        LoginUser user = get();
        if (user.domain() != LoginDomain.ADMIN || user.role() == null) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        for (AdminRole role : allowed) {
            if (user.role() == role) {
                return role;
            }
        }
        throw new BizException(ResultCode.FORBIDDEN);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
