package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.AppConfig;
import com.diting.mapper.AppConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三项产品策略配置（开发文档 §6.4、§8）。
 *
 * 口径要点：
 * - 三个配置作为**同一版本快照**保存，保存时用 expectedVersion 做乐观锁，冲突返回 409；
 * - 默认值只是初值：3 份/日、阈值 60、最长 10 分钟；0 份表示没有免费额度，不是无限；
 * - 业务日期按 Asia/Shanghai 计算，跨日边界由业务时区决定而不是服务器时区。
 */
@Service
public class ConfigService {

    public static final String KEY_DAILY_FREE_QUOTA = "daily_free_quota";
    public static final String KEY_RISK_THRESHOLD = "risk_threshold";
    public static final String KEY_MAX_RECORDING_MINUTES = "max_recording_minutes";

    public static final int DEFAULT_DAILY_FREE_QUOTA = 3;
    public static final int DEFAULT_RISK_THRESHOLD = 60;
    public static final int DEFAULT_MAX_RECORDING_MINUTES = 10;

    /** 合理上限：防止把份数/时长填成天量值，让后面的时长校验形同虚设 */
    public static final int MAX_DAILY_FREE_QUOTA = 10_000;
    public static final int MAX_RECORDING_MINUTES = 600;

    private static final List<String> KEYS = List.of(
        KEY_DAILY_FREE_QUOTA, KEY_RISK_THRESHOLD, KEY_MAX_RECORDING_MINUTES);

    private final AppConfigMapper appConfigMapper;
    private Clock clock = Clock.systemUTC();

    public ConfigService(AppConfigMapper appConfigMapper) {
        this.appConfigMapper = appConfigMapper;
    }

    /** 仅测试使用：注入可控时钟 */
    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    /**
     * 当前策略快照（读：不写库）。
     * P2 修复前 load() 在缺行时做 upsert，而 quota 快照、任务提交、每次分析
     * 都在热路径调它——读放大成写。缺行只发生在首启/迁移后，补齐移交给
     * ensureDefaults()（启动时一次）与 save()。
     */
    public PolicyConfig load() {
        return toPolicy(readAll());
    }

    /**
     * 启动时补齐缺省行（幂等）：只在部署启动路径调用，不在热路径调用。
     * 并发初始化只插入缺行，绝不覆盖另一事务刚保存的值。
     */
    @Transactional
    public PolicyConfig ensureDefaults() {
        Map<String, AppConfig> rows = readAll();
        if (rows.size() == KEYS.size()) {
            return toPolicy(rows);
        }
        long nextVersion = maxVersion(rows) + 1;
        LocalDateTime now = BusinessTime.now(clock);
        Map<String, Integer> defaults = Map.of(
            KEY_DAILY_FREE_QUOTA, DEFAULT_DAILY_FREE_QUOTA,
            KEY_RISK_THRESHOLD, DEFAULT_RISK_THRESHOLD,
            KEY_MAX_RECORDING_MINUTES, DEFAULT_MAX_RECORDING_MINUTES);
        for (String key : KEYS) {
            if (!rows.containsKey(key)) {
                appConfigMapper.insertMissing(row(key, String.valueOf(defaults.get(key)), nextVersion, now));
            }
        }
        return toPolicy(readAll());
    }

    /** 保存三项配置；expectedVersion 与当前不一致时返回 409 */
    @Transactional
    public PolicyConfig save(long expectedVersion, Integer dailyFreeQuota, Integer riskThreshold,
                             Integer maxRecordingMinutes) {
        assertRange(dailyFreeQuota, riskThreshold, maxRecordingMinutes);
        Map<String, AppConfig> rows = readAll();
        long currentVersion = maxVersion(rows);
        if (expectedVersion != currentVersion) {
            throw new BizException(ResultCode.CONFLICT,
                "配置已被更新（当前版本 " + currentVersion + "），请刷新后重试");
        }
        long nextVersion = currentVersion + 1;
        LocalDateTime now = BusinessTime.now(clock);
        saveValue(rows, KEY_DAILY_FREE_QUOTA, String.valueOf(dailyFreeQuota), nextVersion, now);
        saveValue(rows, KEY_RISK_THRESHOLD, String.valueOf(riskThreshold), nextVersion, now);
        saveValue(rows, KEY_MAX_RECORDING_MINUTES, String.valueOf(maxRecordingMinutes), nextVersion, now);
        return toPolicy(readAll());
    }

    /** 业务日期（Asia/Shanghai） */
    public LocalDate businessDate() {
        return businessDate(clock);
    }

    static LocalDate businessDate(Clock clock) {
        return BusinessTime.today(clock);
    }

    private void assertRange(Integer dailyFreeQuota, Integer riskThreshold, Integer maxRecordingMinutes) {
        if (dailyFreeQuota == null || dailyFreeQuota < 0 || dailyFreeQuota > MAX_DAILY_FREE_QUOTA) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "每日免费份数需为 0-" + MAX_DAILY_FREE_QUOTA + " 的整数（0 表示没有免费额度）");
        }
        if (riskThreshold == null || riskThreshold < 0 || riskThreshold > 100) {
            throw new BizException(ResultCode.UNPROCESSABLE, "风险阈值需为 0-100 的整数");
        }
        if (maxRecordingMinutes == null || maxRecordingMinutes <= 0
            || maxRecordingMinutes > MAX_RECORDING_MINUTES) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "最长录音时长需为 1-" + MAX_RECORDING_MINUTES + " 分钟的整数");
        }
    }

    private Map<String, AppConfig> readAll() {
        List<AppConfig> rows = appConfigMapper.selectList(new LambdaQueryWrapper<AppConfig>()
            .in(AppConfig::getConfigKey, KEYS));
        Map<String, AppConfig> byKey = new HashMap<>();
        for (AppConfig row : rows) {
            byKey.put(row.getConfigKey(), row);
        }
        return byKey;
    }

    private static AppConfig row(String key, String value, long version, LocalDateTime now) {
        AppConfig config = new AppConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setVersion(version);
        config.setUpdatedAt(now);
        return config;
    }

    private void saveValue(Map<String, AppConfig> snapshot, String key, String value, long version, LocalDateTime now) {
        AppConfig previous = snapshot.get(key);
        AppConfig next = row(key, value, version, now);
        try {
            int affected = previous == null ? appConfigMapper.insert(next)
                : appConfigMapper.compareAndSet(next, previous.getVersion() == null ? 0L : previous.getVersion());
            if (affected != 1) {
                throw new BizException(ResultCode.CONFLICT, "配置已被更新，请刷新后重试");
            }
        }
        catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BizException(ResultCode.CONFLICT, "配置已被初始化，请刷新后重试");
        }
    }

    private static long maxVersion(Map<String, AppConfig> rows) {
        return rows.values().stream()
            .mapToLong(row -> row.getVersion() == null ? 0L : row.getVersion())
            .max()
            .orElse(0L);
    }

    private static PolicyConfig toPolicy(Map<String, AppConfig> rows) {
        return new PolicyConfig(
            maxVersion(rows),
            valueOf(rows, KEY_DAILY_FREE_QUOTA, DEFAULT_DAILY_FREE_QUOTA),
            valueOf(rows, KEY_RISK_THRESHOLD, DEFAULT_RISK_THRESHOLD),
            valueOf(rows, KEY_MAX_RECORDING_MINUTES, DEFAULT_MAX_RECORDING_MINUTES)
        );
    }

    private static int valueOf(Map<String, AppConfig> rows, String key, int fallback) {
        AppConfig row = rows.get(key);
        if (row == null || row.getConfigValue() == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(row.getConfigValue().trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 策略快照：三项配置 + 版本 */
    public record PolicyConfig(long version, int dailyFreeQuota, int riskThreshold,
                               int maxRecordingMinutes) {
    }
}
