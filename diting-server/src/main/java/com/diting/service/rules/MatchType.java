package com.diting.service.rules;

import java.util.List;
import java.util.Locale;

/** 规则类型（开发文档 §8）：hit / require / must-have */
public enum MatchType {

    /** 指定角色语句包含任一关键词即命中 */
    HIT("hit"),
    /** 触发角色命中触发词后，随后 N 条目标角色发言内应包含任一应答词 */
    REQUIRE("require"),
    /** 指定角色整通话未出现必需词，记为通话级缺失 */
    MUST_HAVE("must-have");

    private final String value;

    MatchType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MatchType of(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (MatchType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }

    public static List<String> allowedValues() {
        return List.of(HIT.value, REQUIRE.value, MUST_HAVE.value);
    }
}
