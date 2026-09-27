package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.AppConfig;
import com.diting.mapper.AppConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S03 配置侧：默认值、范围校验、版本乐观锁、业务日边界。
 * mapper 用桩，逻辑用真实实现。
 */
class ConfigServiceTest {

    private final Map<String, AppConfig> store = new HashMap<>();
    private AppConfigMapper mapper;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        store.clear();
        mapper = mock(AppConfigMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(mapper.selectById(any())).thenAnswer(inv -> store.get(String.valueOf((Object) inv.getArgument(0))));
        when(mapper.insert(any(AppConfig.class))).thenAnswer(inv -> {
            AppConfig row = inv.getArgument(0);
            store.put(row.getConfigKey(), row);
            return 1;
        });
        when(mapper.insertMissing(any())).thenAnswer(inv -> {
            AppConfig row = inv.getArgument(0);
            return store.putIfAbsent(row.getConfigKey(), row) == null ? 1 : 0;
        });
        when(mapper.compareAndSet(any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            AppConfig next = inv.getArgument(0);
            AppConfig old = store.get(next.getConfigKey());
            long expected = inv.getArgument(1);
            if (old == null || old.getVersion() != expected) return 0;
            store.put(next.getConfigKey(), next);
            return 1;
        });
        service = new ConfigService(mapper);
    }

    @Test
    @DisplayName("首次读取补齐默认值：3 份 / 阈值 60 / 10 分钟")
    void seedsDefaults() {
        // P2 修复：load() 纯读不写库，补齐走启动时的 ensureDefaults()
        ConfigService.PolicyConfig config = service.ensureDefaults();

        assertThat(config.dailyFreeQuota()).isEqualTo(3);
        assertThat(config.riskThreshold()).isEqualTo(60);
        assertThat(config.maxRecordingMinutes()).isEqualTo(10);
        assertThat(config.version()).isEqualTo(1);
        assertThat(store).hasSize(3);
    }

    @Test
    @DisplayName("load() 纯读：缺行时不写库，返回默认值")
    void loadIsReadOnly() {
        ConfigService.PolicyConfig config = service.load();

        assertThat(config.dailyFreeQuota()).isEqualTo(3);
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("范围校验：份数≥0、阈值 0-100、时长>0")
    void validatesRange() {
        assertThatThrownBy(() -> service.save(0L, -1, 60, 10)).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode()).isEqualTo(ResultCode.UNPROCESSABLE);
        assertThatThrownBy(() -> service.save(0L, 3, 101, 10)).isInstanceOf(BizException.class)
            .hasMessageContaining("风险阈值");
        assertThatThrownBy(() -> service.save(0L, 3, 60, 0)).isInstanceOf(BizException.class)
            .hasMessageContaining("最长录音时长");
        assertThatThrownBy(() -> service.save(0L, 3, 60, 601)).isInstanceOf(BizException.class)
            .hasMessageContaining("最长录音时长");
    }

    @Test
    @DisplayName("0 份是合法值（表示没有免费额度），保存后版本 +1")
    void zeroQuotaIsValid() {
        service.ensureDefaults();
        ConfigService.PolicyConfig saved = service.save(1L, 0, 80, 5);

        assertThat(saved.dailyFreeQuota()).isZero();
        assertThat(saved.riskThreshold()).isEqualTo(80);
        assertThat(saved.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("版本冲突：expectedVersion 落后时返回 409，不覆盖别人刚改的配置")
    void versionConflict() {
        service.ensureDefaults();
        service.save(1L, 5, 70, 12);

        assertThatThrownBy(() -> service.save(1L, 9, 90, 20))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
        assertThat(service.load().dailyFreeQuota()).isEqualTo(5);
    }

    @Test
    void lostCasRejectsInsteadOfOverwriting() {
        service.ensureDefaults();
        org.mockito.Mockito.doReturn(0).when(mapper).compareAndSet(any(), org.mockito.ArgumentMatchers.anyLong());
        assertThatThrownBy(() -> service.save(1L, 9, 90, 20)).isInstanceOf(BizException.class);
        assertThat(service.load().dailyFreeQuota()).isEqualTo(3);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(AppConfig.class));
    }

    @Test
    void emptyConfigAlsoChecksExpectedVersion() {
        assertThatThrownBy(() -> service.save(99L, 3, 60, 10)).isInstanceOf(BizException.class);
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("业务日边界：Asia/Shanghai 的 00:00 而不是 UTC 的 00:00")
    void businessDateUsesShanghaiZone() {
        assertThat(ConfigService.businessDate(Clock.fixed(Instant.parse("2026-09-24T15:59:59Z"), ZoneOffset.UTC)))
            .isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(ConfigService.businessDate(Clock.fixed(Instant.parse("2026-09-24T16:00:00Z"), ZoneOffset.UTC)))
            .isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    @DisplayName("读取接口解析历史值时容错：非法数值回落默认值")
    void tolerantParsing() {
        AppConfig quota = new AppConfig();
        quota.setConfigKey(ConfigService.KEY_DAILY_FREE_QUOTA);
        quota.setConfigValue("not-a-number");
        quota.setVersion(4L);
        store.put(ConfigService.KEY_DAILY_FREE_QUOTA, quota);

        assertThat(service.load().dailyFreeQuota()).isEqualTo(ConfigService.DEFAULT_DAILY_FREE_QUOTA);
    }
}
