package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台账号（开发文档 §6.4）。
 * role 取 ADMIN / OPERATOR / INVESTOR；密码只存 BCrypt 哈希，初始化不得使用默认弱密码。
 */
@Data
@TableName("admin_users")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，不外泄 */
    private String passwordHash;

    private String nickname;

    /** ADMIN / OPERATOR / INVESTOR */
    private String role;

    /** 禁用后所有请求立即拒绝 */
    private Boolean enabled;

    /** 软删除时间：非空即已删除。账号与审计日志强绑定，所以不做物理删除 */
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
