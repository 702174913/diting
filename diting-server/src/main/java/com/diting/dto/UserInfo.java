package com.diting.dto;

import com.diting.entity.User;

/**
 * C 端用户信息（开发文档 §7.1）。
 * 只暴露必要字段：密码哈希、openid 明文一律不出现在响应里。
 */
public record UserInfo(
    Long id,
    String username,
    String nickname,
    boolean hasPassword,
    boolean hasWechat
) {

    public static UserInfo of(User user) {
        return new UserInfo(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getPasswordHash() != null,
            user.getWechatOpenid() != null
        );
    }
}
