package com.diting.dto;

/** 后台登录结果（开发文档 §7.1）：token、角色、昵称；与 C 端 token 不可互用 */
public record AdminLoginResult(String token, String role, String nickname) {
}
