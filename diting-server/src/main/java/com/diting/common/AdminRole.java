package com.diting.common;

import lombok.Getter;

/**
 * 后台角色（开发文档 §9 权限矩阵）。
 * 权限判定只在服务端生效，前端视图切换不构成权限。
 */
@Getter
public enum AdminRole {

    /** 全部能力：账号/规则/策略/原件授权/删除/审计 */
    ADMIN,
    /** 可读脱敏报告、可确认角色；不可看原件、不可改规则与账号 */
    OPERATOR,
    /** 只看聚合数据，无个体数据明细 */
    INVESTOR;

    public static AdminRole of(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }
}
