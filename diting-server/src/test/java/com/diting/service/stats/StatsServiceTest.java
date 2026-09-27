package com.diting.service.stats;

import com.diting.common.BizException;
import com.diting.mapper.StatsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 统计口径纪律（开发文档 §10）。
 *
 * 这里守的是三条不能退让的规则：
 * 1. 零分母返回 null，不伪造 0%/100%；
 * 2. REAL 指标必须能追到来源表（没有来源表就不许叫 REAL）；
 * 3. 统计响应里不出现个体标识字段（个体数据走别的接口）。
 *
 * 统计面收敛后只测总览分组；已下线的分组（growth/finance/ops/map/quality/compliance/violations）
 * 仍必须把 404 钉住 —— 它们的前缀以前是能返回 200 的。
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsServiceTest {

    private StatsMapper mapper;
    private StatsService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(StatsMapper.class);
        // 默认桩：空集合与空 Map，避免每个用例重复声明
        when(mapper.queueByStatus()).thenReturn(List.of());
        when(mapper.failureByStatus(any(), any())).thenReturn(List.of());
        when(mapper.durationStats(any(), any())).thenReturn(new LinkedHashMap<>());
        when(mapper.publishedByDay(any(), any())).thenReturn(List.of());
        when(mapper.registrationsByDay(any(), any())).thenReturn(List.of());
        service = new StatsService(mapper);
    }

    private static StatsService.Section section(StatsService service, String name) {
        return service.of(name, "30d", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    private static StatsService.Metric metric(StatsService.Section section, String key) {
        return section.metrics().stream()
            .filter(item -> item.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少指标：" + key + "，" + section.metrics().stream()
                .map(StatsService.Metric::key).toList()));
    }

    private static StatsService.Breakdown breakdown(StatsService.Section section, String key) {
        return section.breakdown().stream()
            .filter(item -> item.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("缺少分组明细：" + key));
    }

    @Test
    @DisplayName("零分母返回 null：没有新增注册时上传率是「暂无数据」而不是 0%")
    void zeroDenominatorReturnsNull() {
        StatsService.Section overview = section(service, "overview");

        StatsService.Metric uploadRate = metric(overview, "uploadRate");
        assertThat(uploadRate.value()).isNull();
        assertThat(uploadRate.denominator()).isNull();
        assertThat(uploadRate.source()).isEqualTo(StatsService.Source.REAL);
        assertThat(uploadRate.note()).contains("暂不计算");

        StatsService.Metric failureRate = metric(overview, "failureRate");
        assertThat(failureRate.value()).isNull();
        assertThat(failureRate.note()).contains("没有终态任务");
    }

    @Test
    @DisplayName("有分母时按真实样本计算，并带上分母与样本量")
    void ratioUsesRealDenominator() {
        when(mapper.registeredUsers(any(), any())).thenReturn(200L);
        when(mapper.uploadingUsers(any(), any())).thenReturn(50L);

        StatsService.Metric uploadRate = metric(section(service, "overview"), "uploadRate");

        assertThat(uploadRate.value()).isEqualByComparingTo(BigDecimal.valueOf(25.0));
        assertThat(uploadRate.denominator()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(uploadRate.sampleSize()).isEqualTo(200L);
    }

    @Test
    @DisplayName("用户漏斗全部按用户去重，且不含付费这类没有数据源的环节")
    void funnelIsUserBasedOnly() {
        when(mapper.registeredUsers(any(), any())).thenReturn(100L);
        when(mapper.consentedUsers(any(), any())).thenReturn(80L);
        when(mapper.uploadingUsers(any(), any())).thenReturn(60L);
        when(mapper.activeUsers(any(), any())).thenReturn(40L);
        when(mapper.reportedUsers(any(), any())).thenReturn(30L);
        when(mapper.exportedUsers(any(), any())).thenReturn(10L);

        StatsService.Breakdown funnel = breakdown(section(service, "overview"), "userFunnel");

        assertThat(funnel.rows()).extracting(StatsService.Row::key)
            .containsExactly("registered", "consented", "uploaded", "submitted", "reported", "exported");
        assertThat(funnel.rows()).extracting(StatsService.Row::value)
            .containsExactly(BigDecimal.valueOf(100), BigDecimal.valueOf(80), BigDecimal.valueOf(60),
                BigDecimal.valueOf(40), BigDecimal.valueOf(30), BigDecimal.valueOf(10));
    }

    @Test
    @DisplayName("按日序列补零：日期连续，跳过的日子是 0 而不是消失")
    void dailySeriesFillsZeros() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("day", "2026-09-03");
        row.put("cnt", 4L);
        when(mapper.registrationsByDay(any(), any())).thenReturn(List.of(row));

        StatsService.Series daily = section(service, "overview").series().stream()
            .filter(item -> item.key().equals("dailyRegistrations"))
            .findFirst()
            .orElseThrow();

        assertThat(daily.points()).hasSize(30);
        assertThat(daily.points().get(2).value()).isEqualByComparingTo(BigDecimal.valueOf(4));
        assertThat(daily.points().get(3).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("可追溯纪律：REAL 指标必须有来源表，PENDING/DEMO 不许带数值")
    void everyMetricIsTraceable() {
        List<String> sections = List.of("overview");
        List<String> problems = new ArrayList<>();
        for (String name : sections) {
            for (StatsService.Metric item : section(service, name).metrics()) {
                if (item.source() == StatsService.Source.REAL && item.value() != null
                    && (item.sourceTable() == null || item.sourceTable().isBlank())) {
                    problems.add(name + "." + item.key() + " 标了 REAL 却没有来源表");
                }
                if (item.source() != StatsService.Source.REAL && item.value() != null) {
                    problems.add(name + "." + item.key() + " 不是 REAL 却带了数值");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("已下线的统计分组返回 404，而不是悄悄返回空面板")
    void retiredSectionsFail() {
        for (String name : List.of("growth", "finance", "ops", "map", "quality", "compliance", "violations")) {
            assertThatThrownBy(() -> section(service, name))
                .as(name + " 已随页面下线")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的统计分组");
        }
    }
    @Test
    @DisplayName("统计响应不含个体标识：字段名里不能出现 userId/taskId/sentenceId")
    void noIndividualIdentifiersInStatsResponse() {
        when(mapper.registeredUsers(any(), any())).thenReturn(10L);
        List<String> leaked = new ArrayList<>();
        for (String name : List.of("overview")) {
            StatsService.Section current = section(service, name);
            for (StatsService.Series series : current.series()) {
                if (series.key().toLowerCase().contains("userid")) {
                    leaked.add(name + "." + series.key());
                }
            }
            for (StatsService.Breakdown item : current.breakdown()) {
                for (StatsService.Row row : item.rows()) {
                    String key = row.key() == null ? "" : row.key().toLowerCase();
                    if (key.contains("userid") || key.contains("taskid") || key.contains("sentenceid")) {
                        leaked.add(name + "." + item.key() + "." + row.key());
                    }
                }
            }
        }
        assertThat(leaked).isEmpty();
    }

    @Test
    @DisplayName("兼容旧调用：只给指标的构造方式仍然可用，序列与明细不是 null")
    void legacyConstructorStillWorks() {
        StatsService.Section legacy = new StatsService.Section("x", "7d", "2026-09-01", "2026-09-07", List.of());

        assertThat(legacy.series()).isEmpty();
        assertThat(legacy.breakdown()).isEmpty();
    }

    @Test
    @DisplayName("区间解析：显式 from/to 优先，period 只认四个值")
    void resolveRangeValidatesInput() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 9);
        assertThat(StatsService.resolveRange("30d", from, to)).containsExactly(from, to);

        assertThatThrownBy(() -> StatsService.resolveRange("30d", to, from))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> StatsService.resolveRange("999d", null, null))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("period");
    }

}
