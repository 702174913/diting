package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 账号密码登录的用户名，可空（纯微信用户） */
    private String username;

    private String passwordHash;

    /** 微信 openid，一个 openid 只绑定一个账号 */
    private String wechatOpenid;

    private String unionid;

    private String nickname;

    private LocalDateTime createdAt;

    /** 抹除请求一经提交即撤销既有 JWT；旧记录保留供异步清理作业定位。 */
    private LocalDateTime erasedAt;
}
