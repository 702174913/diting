package com.diting.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时间入口（开发文档 §6「业务日界按 Asia/Shanghai」）。
 *
 * 为什么统一走这里：库里既有数据是东八区本地时间，JDBC 连接也按 Asia/Shanghai 解释；
 * 如果各处用 `LocalDateTime.now()`（跟随 JVM 默认时区）或 `Clock.systemUTC()`，
 * 写入的时间会相差 8 小时，跨“今天/昨天”的判断与 SQL 侧的 NOW(3) 比较都会出错。
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessTime() {
    }

    /** 业务时区的当前时间（与库中历史数据语义一致） */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 供可注入时钟的服务使用：把时钟切到业务时区再取本地时间 */
    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock.withZone(ZONE));
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
