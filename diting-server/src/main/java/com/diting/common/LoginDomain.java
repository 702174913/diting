package com.diting.common;

import lombok.Getter;

/**
 * 身份域：C 端用户与后台账号是两套独立体系。
 * JWT 的 aud 与域声明都取自这里，数字 ID 相同也不能互相冒充。
 */
@Getter
public enum LoginDomain {

    USER("diting-user"),
    ADMIN("diting-admin");

    /** JWT audience 值 */
    private final String audience;

    LoginDomain(String audience) {
        this.audience = audience;
    }
}
