package com.diting.dto;

import com.diting.entity.User;

/** C 端登录结果（开发文档 §7.1）：token + userInfo + 是否新用户 */
public record LoginResult(String token, UserInfo userInfo, boolean isNewUser) {

    public static LoginResult of(String token, User user, boolean isNewUser) {
        return new LoginResult(token, UserInfo.of(user), isNewUser);
    }
}
