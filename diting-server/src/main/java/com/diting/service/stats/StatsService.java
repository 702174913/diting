package com.diting.service.stats;

import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.mapper.StatsMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实统计（开发文档 §10）。
 *
 * 三条纪律：
 * 1. **每个数字都可追溯**：指标带来源表/字段、时间字段、分母与样本量；
 * 2. **零分母返回 null**：不伪造 100%，界面显示「暂无数据」；
 * 3. **没有数据源的指标一律标 PENDING/DEMO**：准确率、误报漏报、收入、渠道、健康分
 *    不接真，也不拿 confidence 冒充准确率。
 *
 * S17 补充：除了「指标」还返回「序列」与「分组明细」，让聚合形状也能接真 —— 但仍然只允许聚合，
 * 个体标识不进本接口。
 *
 * 统计面收敛（本轮）：后台只保留总览看板一个统计页，本服务只服务 `section=overview`；
 * 其余分组（ops / compliance / violations / growth / quality / finance）连同页面一起下线，
 * 未知分组（含上述已下线名字）仍返回 404，不静默返回空面板。
 */
@Service
public class StatsService {

    /** 指标的来源状态：REAL 有来源、DEMO 演示数据、PENDING 待采集 */
    public enum Source {
        REAL, DEMO, PENDING
    }

    public record Metric(String key, String label, BigDecimal value, String unit,
                         String sourceTable, String timeField, BigDecimal denominator,
                         Long sampleSize, Source source, String note) {
    }

    /** 序列上的一个点：key 用于排序/定位，label 用于展示 */
    public record Point(String key, String label, BigDecimal value) {
    }

    /** 一条序列：如「有效报告按日」「新增注册按日」 */
    public record Series(String key, String label, String unit, List<Point> points, String note) {
    }

    /** 分组明细的一行：value 是主数值，note 放口径补充（如涉及通话数、占比） */
    public record Row(String key, String label, BigDecimal value, String note) {
    }

    /** 分组明细：如「用户转化漏斗」 */
    public record Breakdown(String key, String label, String unit, List<Row> rows, String note) {
    }

    public record Section(String section, String period, String from, String to,
                          List<Metric> metrics, List<Series> series, List<Breakdown> breakdown) {

        /** 兼容旧调用：只有指标时序列与明细为空，不返回 null */
        public Section(String section, String period, String from, String to, List<Metric> metrics) {
            this(section, period, from, to, metrics, List.of(), List.of());
        }
    }

    private final StatsMapper statsMapper;

    public StatsService(StatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    public Section of(String section, String period, LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        return switch (section) {
            case "overview" -> overview(period, start, end, from, to);
            default -> throw new BizException(ResultCode.NOT_FOUND, "未知的统计分组：" + section);
        };
    }

    // =================================================================
    // 总览：北极星（有效报告）+ 真实用户漏斗（全部按用户去重，不混算任务数）
    // =================================================================
    private Section overview(String period, LocalDateTime start, LocalDateTime end,
                             LocalDate from, LocalDate to) {
        long submitted = statsMapper.submittedTasks(start, end);
        long reports = statsMapper.publishedReports(start, end);
        long completed = statsMapper.completedTasks(start, end);
        Map<String, Long> failures = toMap(statsMapper.failureByStatus(start, end));
        long failed = failures.getOrDefault("FAILED", 0L);
        long invalid = failures.getOrDefault("INVALID", 0L);
        Map<String, Object> duration = statsMapper.durationStats(start, end);
        long durationSamples = asLong(duration.get("samples"));
        BigDecimal avgSeconds = durationSamples == 0 ? null : BigDecimal.valueOf(asDouble(duration.get("avg_seconds")))
            .setScale(0, RoundingMode.HALF_UP);

        long registered = statsMapper.registeredUsers(start, end);
        long consented = statsMapper.consentedUsers(start, end);
        long uploading = statsMapper.uploadingUsers(start, end);
        long active = statsMapper.activeUsers(start, end);
        long reported = statsMapper.reportedUsers(start, end);
        long exported = statsMapper.exportedUsers(start, end);
        long confirmed = statsMapper.confirmedTasks(start, end);

        List<Metric> metrics = new ArrayList<>();
        metrics.add(metric("publishedReports", "有效报告量", BigDecimal.valueOf(reports), "份",
            "report_versions", "published_at（按 task 取首个发布）", null, null, Source.REAL,
            "只统计全历史首次发布落在时间窗内的任务；重算不计入新报告"));
        metrics.add(metric("registeredUsers", "新增注册", BigDecimal.valueOf(registered), "人",
            "users", "created_at", null, null, Source.REAL, "以 users.created_at 为准，不依赖前端埋点"));
        metrics.add(metric("activeUsers", "活跃用户（提交过质检）", BigDecimal.valueOf(active), "人",
            "recognition_tasks", "created_at", null, null, Source.REAL, "按 user 去重"));
        metrics.add(metric("uploadRate", "上传率", ratio(uploading, registered), "%",
            "upload_sessions", "created_at", denominator(registered), registered, Source.REAL,
            registered == 0 ? "区间内没有新增注册，暂不计算" : "分母=区间新增注册用户数"));
        metrics.add(metric("submittedTasks", "提交任务量", BigDecimal.valueOf(submitted), "个",
            "recognition_tasks", "created_at", null, null, Source.REAL, "按 task 去重"));
        metrics.add(metric("queueNow", "当前队列", BigDecimal.valueOf(queueTotal()), "个",
            "recognition_tasks", "status（时点库存）", null, null, Source.REAL,
            "时点库存，不与期间处理量相加"));
        metrics.add(metric("completedTasks", "完成量", BigDecimal.valueOf(completed), "个",
            "recognition_tasks", "finished_at", null, null, Source.REAL, null));
        metrics.add(metric("failedTasks", "失败量", BigDecimal.valueOf(failed), "个",
            "recognition_tasks", "finished_at", null, null, Source.REAL, "仅统计 FAILED，不含 INVALID"));
        metrics.add(metric("invalidTasks", "无有效语音", BigDecimal.valueOf(invalid), "个",
            "recognition_tasks", "finished_at", null, null, Source.REAL, "单列，不并入失败率"));
        long denominator = completed + failed + invalid;
        metrics.add(metric("failureRate", "失败率", ratio(failed, denominator), "%",
            "recognition_tasks", "finished_at", denominator(denominator), denominator, Source.REAL,
            denominator == 0 ? "区间内没有终态任务，暂不计算" : "分母=完成+失败+无有效语音"));
        metrics.add(metric("avgDuration", "平均耗时", avgSeconds, "秒",
            "recognition_tasks", "finished_at - created_at", null, durationSamples, Source.REAL,
            durationSamples == 0 ? "区间内没有可计算的完成样本" : "只统计时间字段齐全的样本"));
        metrics.add(metric("confirmCoverage", "角色确认覆盖率", ratio(confirmed, completed), "%",
            "audit_log", "SPEAKER_MAP_CONFIRM", denominator(completed), completed, Source.REAL,
            completed == 0 ? "区间内没有完成任务，暂不计算" : "分母=完成量；只证明有人确认过角色，不代表判读正确"));

        List<Series> series = List.of(
            series("dailyReports", "有效报告", "份", fillByDay(statsMapper.publishedByDay(start, end), from, to),
                "每天发布的首个报告版本，按 task 去重"),
            series("dailyRegistrations", "新增注册", "人", fillByDay(statsMapper.registrationsByDay(start, end), from, to),
                "users.created_at 按日"));

        List<Breakdown> breakdown = List.of(
            breakdown("userFunnel", "用户转化漏斗", "人", List.of(
                row("registered", "完成注册", registered, "区间新增注册用户"),
                row("consented", "完成授权", consented, consented == 0 ? null : percentOf(consented, registered)),
                row("uploaded", "上传过文件", uploading, uploading == 0 ? null : percentOf(uploading, registered)),
                row("submitted", "提交过质检", active, active == 0 ? null : percentOf(active, registered)),
                row("reported", "产出过报告", reported, reported == 0 ? null : percentOf(reported, registered)),
                row("exported", "导出过产物", exported, exported == 0 ? null : percentOf(exported, registered))),
                "全部按用户去重，逐环是上一步的子集；付费环节无数据源，不展示"));

        return new Section("overview", period, from.toString(), to.toString(), metrics, series, breakdown);
    }

    // =================================================================
    // 工具
    // =================================================================

    private long queueTotal() {
        long total = 0;
        for (Map<String, Object> row : statsMapper.queueByStatus()) {
            total += asLong(row.get("cnt"));
        }
        return total;
    }

    /** 比率：分母为 0 或为 null 时返回 null，绝不伪造成 0% 或 100% */
    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal denominator(long value) {
        return value <= 0 ? null : BigDecimal.valueOf(value);
    }

    private static String percentOf(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private static Point point(String key, String label, long value) {
        return new Point(key, label, BigDecimal.valueOf(value));
    }

    private static Series series(String key, String label, String unit, List<Point> points, String note) {
        return new Series(key, label, unit, points, note);
    }

    private static Row row(String key, String label, long value, String note) {
        return new Row(key, label, BigDecimal.valueOf(value), note);
    }

    private static Breakdown breakdown(String key, String label, String unit, List<Row> rows, String note) {
        return new Breakdown(key, label, unit, rows, note);
    }

    /** 按日序列补零：区间内没有数据的日子必须出现 0，否则图表会骗人 */
    private static List<Point> fillByDay(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byDay.put(String.valueOf(row.get("day")), asLong(row.get("cnt")));
        }
        List<Point> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String key = day.toString();
            points.add(point(key, key.substring(5), byDay.getOrDefault(key, 0L)));
        }
        return points;
    }

    private static Metric metric(String key, String label, BigDecimal value, String unit,
                                 String sourceTable, String timeField, BigDecimal denominator,
                                 Long sampleSize, Source source, String note) {
        return new Metric(key, label, value, unit, sourceTable, timeField, denominator, sampleSize,
            source, note);
    }

    private static Map<String, Long> toMap(List<Map<String, Object>> rows) {
        return toMapByKey(rows, "status");
    }

    private static Map<String, Long> toMapByKey(List<Map<String, Object>> rows, String key) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(String.valueOf(row.get(key)), asLong(row.get("cnt")));
        }
        return out;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    /** 供控制器解析区间：默认最近 7 天（含今天） */
    public static LocalDate[] resolveRange(String period, LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new BizException(ResultCode.UNPROCESSABLE, "起始日期不能晚于结束日期");
            }
            return new LocalDate[]{from, to};
        }
        String key = period == null ? "7d" : period.trim().toLowerCase();
        return switch (key) {
            case "today" -> new LocalDate[]{today, today};
            case "30d" -> new LocalDate[]{today.minusDays(29), today};
            case "quarter" -> new LocalDate[]{today.minusDays(89), today};
            case "7d" -> new LocalDate[]{today.minusDays(6), today};
            default -> throw new BizException(ResultCode.UNPROCESSABLE,
                "period 只能是 today/7d/30d/quarter，或传 from+to");
        };
    }
}
