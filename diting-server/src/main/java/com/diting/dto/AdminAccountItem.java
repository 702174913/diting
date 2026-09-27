package com.diting.dto;

import com.diting.entity.AdminUser;

import java.time.LocalDateTime;

/** 后台账号列表项：不含密码哈希 */
public record AdminAccountItem(
    Long id,
    String username,
    String nickname,
    String role,
    boolean enabled,
    LocalDateTime createdAt,
    /** 非空表示已软删除；列表默认不返回这类行，需要时用 includeDeleted 带上 */
    LocalDateTime deletedAt
) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public static AdminAccountItem of(AdminUser user) {
        return new AdminAccountItem(user.getId(), user.getUsername(), user.getNickname(),
            user.getRole(), Boolean.TRUE.equals(user.getEnabled()), user.getCreatedAt(),
            user.getDeletedAt());
    }
}
