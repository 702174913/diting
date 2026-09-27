package com.diting.config;

import com.diting.common.AdminRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后台接口角色限制（开发文档 §9 权限矩阵）。
 * 标在方法或类上，未标注的后台接口默认所有后台角色可访问；
 * 判定在服务端拦截器内完成，前端视图切换与菜单隐藏都不构成权限。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    AdminRole[] value();
}
