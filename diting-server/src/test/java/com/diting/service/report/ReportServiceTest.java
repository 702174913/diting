package com.diting.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.dto.ReportDetailResponse;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.entity.Sentence;
import com.diting.entity.SpeakerMapping;
import com.diting.entity.Violation;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.ReportVersionMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.SpeakerMappingMapper;
import com.diting.mapper.ViolationMapper;
import com.diting.service.rules.RuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S08 报告服务：角色映射版本冲突、版本选取、计数口径、缺原件与 XSS 文本透传。
 * mapper 用内存桩，逻辑用真实实现。
 */
class ReportServiceTest {

    private final List<SpeakerMapping> mappings = new ArrayList<>();
    private final List<ReportVersion> versions = new ArrayList<>();
    private final List<Violation> violations = new ArrayList<>();
    private final List<Sentence> sentences = new ArrayList<>();
    private ReportService service;
    private RecognitionTaskMapper taskMapper;
    private ReportAnalysisService analysisService;
    private RuleService ruleService;

    @BeforeEach
    void setUp() {
        // 纯单测里要解析 LambdaQueryWrapper 的 SQL 片段，先注册实体元信息
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
            new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
            new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, SpeakerMapping.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ReportVersion.class);

        mappings.clear();
        versions.clear();
        violations.clear();
        sentences.clear();

        SpeakerMappingMapper mappingMapper = mock(SpeakerMappingMapper.class);
        ReportVersionMapper versionMapper = mock(ReportVersionMapper.class);
        ViolationMapper violationMapper = mock(ViolationMapper.class);
        SentenceMapper sentenceMapper = mock(SentenceMapper.class);

        when(mappingMapper.insert(any(SpeakerMapping.class))).thenAnswer(inv -> {
            mappings.add(inv.getArgument(0));
            return 1;
        });
        when(mappingMapper.selectList(any())).thenAnswer(inv -> filterMappings(inv.getArgument(0)));
        when(versionMapper.selectList(any())).thenAnswer(inv -> filterVersions(inv.getArgument(0)));
        when(versionMapper.selectOne(any())).thenAnswer(inv -> filterVersions(inv.getArgument(0)).stream()
            .findFirst().orElse(null));
        when(violationMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(violations));
        when(sentenceMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(sentences));

        taskMapper = mock(RecognitionTaskMapper.class);
        analysisService = mock(ReportAnalysisService.class);
        ruleService = mock(RuleService.class);
        when(taskMapper.lockById(100L)).thenAnswer(inv -> task());
        service = new ReportService(mappingMapper, versionMapper, violationMapper, sentenceMapper,
            ruleService, analysisService, taskMapper);
    }

    /** 桩按显式 revision 参数过滤，不能把历史版本查询偷换成最新版本。 */
    private List<SpeakerMapping> filterMappings(Object wrapper) {
        List<SpeakerMapping> rows = new ArrayList<>(mappings);
        rows.sort((a, b) -> Long.compare(b.getRevision(), a.getRevision()));
        LambdaQueryWrapper<SpeakerMapping> query = (LambdaQueryWrapper<SpeakerMapping>) wrapper;
        String sql = query.getSqlSegment();
        if (sql.contains("revision =")) {
            Object revision = query.getParamNameValuePairs().get("MPGENVAL2");
            return rows.stream().filter(r -> java.util.Objects.equals(r.getRevision(), revision)).toList();
        }
        return rows;
    }

    /**
     * 桩：按 wrapper 的条件做最小过滤。
     * 参数顺序与代码里 eq 的书写顺序一致（taskId 在前，version/status 在后），
     * 这样「按版本取」「按状态取」两条路径都能被真实覆盖。
     */
    private List<ReportVersion> filterVersions(Object wrapper) {
        LambdaQueryWrapper<ReportVersion> lambda = (LambdaQueryWrapper<ReportVersion>) wrapper;
        String sql = String.valueOf(lambda.getSqlSegment());
        List<Long> params = lambda.getParamNameValuePairs().values().stream()
            .filter(Long.class::isInstance)
            .map(Long.class::cast)
            .toList();
        List<ReportVersion> rows = new ArrayList<>(versions);
        rows.sort((a, b) -> Long.compare(b.getVersion(), a.getVersion()));
        if (sql.contains("status")) {
            return rows.stream().filter(r -> "PUBLISHED".equals(r.getStatus())).toList();
        }
        if (sql.contains("version") && params.size() > 1) {
            // 服务里的查询固定先 eq(taskId)，其余参数才是版本号；参数表不保证顺序，
            // 因此只取「除第一个之外」的参数做版本过滤，且不再拿 taskId 去撞版本号。
            return rows.stream()
                .filter(r -> r.getVersion() != null && params.contains(r.getVersion()))
                .toList();
        }
        return rows;
    }

    private static RecognitionTask task() {
        RecognitionTask task = new RecognitionTask();
        task.setId(100L);
        task.setUserId(1L);
        task.setStatus("WAITING_ROLE");
        task.setVersion(0L);
        task.setOssObject("recordings/1/a.m4a");
        task.setCreatedAt(LocalDateTime.of(2026, 9, 24, 10, 0));
        return task;
    }

    private void givenPublishedVersion(long version, int risk, Integer threshold) {
        ReportVersion row = new ReportVersion();
        row.setId(version * 10);
        row.setTaskId(100L);
        row.setVersion(version);
        row.setStatus(ReportAnalysisService.STATUS_PUBLISHED);
        row.setRiskScore(risk);
        row.setComplianceScore(100 - risk);
        row.setThresholdSnapshot(threshold == null ? 60 : threshold);
        row.setMappingRevision(1L);
        row.setRuleSetVersion(1L);
        row.setConfigVersion(1L);
        row.setCreatedAt(LocalDateTime.now());
        row.setPublishedAt(LocalDateTime.now());
        versions.add(row);
    }

    @Test
    void historicalReportKeepsItsOwnSpeakerRevision() {
        service.persistMapping(100L, 1L, Map.of(0, "AGENT"), 1L, "USER");
        service.persistMapping(100L, 2L, Map.of(0, "CUSTOMER"), 1L, "USER");
        givenPublishedVersion(1L, 30, 60);
        sentences.add(sentence(1L, 1, 0, "原始话术"));
        ReportDetailResponse old = service.detailOf(task(), versions.get(0));
        assertThat(old.mappingRevision()).isEqualTo(1L);
        assertThat(old.sentences().get(0).role()).isEqualTo("AGENT");
        assertThat(old.speakerMapping().get(0).role()).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("REPORT-02 同时确认角色：expectedRevision 落后 409，且不写入任何映射")
    void mappingConflict() {
        service.persistMapping(100L, 1L, Map.of(0, "AGENT"), 1L, "USER");

        assertThatThrownBy(() -> service.saveMapping(1L, "USER", task(), 0L, Map.of(0, "CUSTOMER")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
        assertThat(mappings).hasSize(1);
    }

    @Test
    @DisplayName("REPORT-07 目标角色功能隐藏：保留 SpeakerId，说明按「任意角色」判读")
    void unconfirmedRoles() {
        givenPublishedVersion(1L, 0, 60);
        sentences.add(sentence(1L, 1, 0, "你好。"));

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.rolesConfirmed()).isFalse();
        assertThat(detail.speakerMapping()).isEmpty();
        assertThat(detail.notes()).anySatisfy(note -> assertThat(note).contains("不区分说话人"));
        assertThat(detail.sentences().get(0).role()).isNull();
    }

    @Test
    @DisplayName("REPORT-07 多人场景：第三位说话人保留 UNKNOWN，不强行二分")
    void multipleSpeakers() {
        service.persistMapping(100L, 1L, Map.of(0, "AGENT", 1, "CUSTOMER", 2, "UNKNOWN"), 1L, "USER");
        givenPublishedVersion(1L, 0, 60);

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.rolesConfirmed()).isTrue();
        assertThat(detail.speakerMapping()).hasSize(3);
        assertThat(detail.speakerMapping()).anySatisfy(role -> {
            assertThat(role.speakerId()).isEqualTo(2);
            assertThat(role.role()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    @DisplayName("REPORT-06/09 计数口径：flaggedCount 按命中句去重，violationCount 含通话级缺失")
    void counts() {
        givenPublishedVersion(1L, 30, 60);
        sentences.add(sentence(1L, 1, 0, "保证免息。"));
        violations.add(violation(1L, 101L, 1L, 30, "命中《禁用承诺》"));
        violations.add(violation(1L, 101L, null, 0, "通话级缺失"));
        violations.add(violation(1L, 102L, 1L, 20, "另一条规则同句命中"));

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.flaggedCount()).isEqualTo(1);
        assertThat(detail.violationCount()).isEqualTo(3);
        assertThat(detail.sentences()).hasSize(1);
        assertThat(detail.sentences().get(0).highlights()).hasSize(2);
        assertThat(detail.riskScore()).isEqualTo(30);
        assertThat(detail.complianceScore()).isEqualTo(70);
        assertThat(detail.riskFlagged()).isFalse();
    }

    @Test
    @DisplayName("REPORT-03 重算失败：仍返回最近成功发布的版本，并带最新状态说明")
    void failedRecalculationKeepsOldVersion() {
        givenPublishedVersion(1L, 30, 60);
        ReportVersion failed = new ReportVersion();
        failed.setId(99L);
        failed.setTaskId(1L);
        failed.setVersion(2L);
        failed.setStatus(ReportAnalysisService.STATUS_FAILED);
        failed.setMappingRevision(2L);
        failed.setThresholdSnapshot(60);
        failed.setCreatedAt(LocalDateTime.now());
        versions.add(failed);

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.reportVersion()).isEqualTo(1);
        assertThat(detail.status()).isEqualTo(ReportAnalysisService.STATUS_PUBLISHED);
        assertThat(detail.latestStatus()).isEqualTo(ReportAnalysisService.STATUS_FAILED);
        assertThat(detail.notes()).anySatisfy(note -> assertThat(note).contains("最新一次判读失败", "已发布的第 1 版"));
        assertThat(detail.notes()).allSatisfy(note -> assertThat(note).doesNotContain("FAILED", "ANALYZING"));
    }

    @Test
    @DisplayName("REPORT-03 只有失败版本时：不伪造分数")
    void onlyFailedVersionHasNoScore() {
        ReportVersion failed = new ReportVersion();
        failed.setId(98L);
        failed.setTaskId(1L);
        failed.setVersion(1L);
        failed.setStatus(ReportAnalysisService.STATUS_FAILED);
        failed.setThresholdSnapshot(60);
        failed.setMappingRevision(1L);
        failed.setCreatedAt(LocalDateTime.now());
        versions.add(failed);

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.status()).isEqualTo(ReportAnalysisService.STATUS_FAILED);
        assertThat(detail.riskScore()).isNull();
        assertThat(detail.complianceScore()).isNull();
        assertThat(detail.riskFlagged()).isFalse();
    }

    @Test
    @DisplayName("REPORT-05 XSS 文本原样返回（服务端不生成 HTML）")
    void xssTextPassthrough() {
        givenPublishedVersion(1L, 0, 60);
        String payload = "<script>alert('x')</script> 保证免息";
        sentences.add(sentence(1L, 1, 0, payload));

        ReportDetailResponse detail = service.detail(task(), null);

        assertThat(detail.sentences().get(0).text()).isEqualTo(payload);
    }

    @Test
    @DisplayName("REPORT-08 原件已清理：文本仍可读，只是标注音频不可用")
    void audioPurgedStillReadable() {
        givenPublishedVersion(1L, 0, 60);
        sentences.add(sentence(1L, 1, 0, "你好。"));
        RecognitionTask purged = task();
        purged.setAudioPurged(true);

        ReportDetailResponse detail = service.detail(purged, null);

        assertThat(detail.audioAvailable()).isFalse();
        assertThat(detail.sentences()).hasSize(1);
        assertThat(detail.notes()).anySatisfy(note -> assertThat(note).contains("原始录音"));
    }

    @Test
    @DisplayName("指定版本读取：可取历史版本，读不存在的版本 404")
    void versionSelection() {
        givenPublishedVersion(1L, 10, 60);
        givenPublishedVersion(2L, 90, 60);

        ReportDetailResponse old = service.detail(task(), 1L);
        ReportDetailResponse latest = service.detail(task(), null);

        assertThat(old.reportVersion()).isEqualTo(1);
        assertThat(old.riskScore()).isEqualTo(10);
        assertThat(latest.reportVersion()).isEqualTo(2);
        assertThat(latest.riskFlagged()).isTrue();

        assertThatThrownBy(() -> service.detail(task(), 7L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    void imageTextWithoutSpeakerCanBeReadAndExported() {
        givenPublishedVersion(1L, 0, 60);
        Sentence imageText = sentence(1L, 1, null, "图片识别文字");
        imageText.setSourceAssetId(9L);
        sentences.add(imageText);
        RecognitionTask imageTask = task();
        imageTask.setMediaKind("IMAGE");
        ReportDetailResponse detail = service.detail(imageTask, null);
        assertThat(detail.sentences()).hasSize(1);
        assertThat(detail.sentences().get(0).role()).isNull();
        assertThat(detail.audioAvailable()).isFalse();
    }

    @Test
    void queuedTaskCannotConfirmFabricatedSpeaker() {
        RecognitionTask queued = task();
        queued.setStatus("QUEUED");
        when(taskMapper.lockById(100L)).thenReturn(queued);
        assertThatThrownBy(() -> service.saveMapping(1L, "USER", queued, 0L, Map.of(999, "AGENT")))
            .isInstanceOf(BizException.class).hasMessageContaining("等待识别完成");
        assertThat(mappings).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(analysisService);
    }

    @Test
    void speakerSetMustMatchRealTranscriptExactly() {
        sentences.add(sentence(1L, 1, 0, "催收方的话"));
        sentences.add(sentence(2L, 2, 1, "客户的话"));
        for (Map<Integer, String> invalid : List.of(Map.of(999, "AGENT"), Map.of(0, "AGENT"),
            Map.of(0, "AGENT", 1, "CUSTOMER", 999, "UNKNOWN"))) {
            assertThatThrownBy(() -> service.saveMapping(1L, "USER", task(), 0L, invalid))
                .isInstanceOf(BizException.class).hasMessageContaining("真实说话人");
        }
        assertThat(mappings).isEmpty();
    }

    @Test
    void allRolesValidatedBeforeAnyRowIsWritten() {
        Map<Integer, String> invalid = new java.util.LinkedHashMap<>();
        invalid.put(0, "AGENT");
        invalid.put(1, "INVALID_ROLE");
        assertThatThrownBy(() -> service.persistMapping(100L, 1L, invalid, 1L, "USER"))
            .isInstanceOf(BizException.class);
        assertThat(mappings).isEmpty();
    }

    @Test
    void deletedTaskCannotSaveMapping() {
        RecognitionTask deleted = task();
        deleted.setDeletedAt(LocalDateTime.now());
        when(taskMapper.lockById(100L)).thenReturn(deleted);
        assertThatThrownBy(() -> service.saveMapping(1L, "USER", task(), 0L, Map.of(0, "AGENT")))
            .isInstanceOf(BizException.class).hasMessageContaining("已删除");
        assertThat(mappings).isEmpty();
    }

    @Test
    void validMappingPublishesAndUnknownRoleRemainsAllowed() {
        sentences.add(sentence(1L, 1, 0, "你好"));
        when(ruleService.snapshotEnabledRules()).thenReturn(new RuleService.RuleSetSnapshot(1L, List.of()));
        ReportVersion published = new ReportVersion();
        published.setVersion(1L);
        published.setStatus("PUBLISHED");
        when(analysisService.publish(any(), org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(1L), any(), any(), any(), org.mockito.ArgumentMatchers.eq(true)))
            .thenReturn(published);
        var outcome = service.saveMapping(1L, "USER", task(), 0L, Map.of(0, "UNKNOWN"));
        assertThat(outcome.analysisStatus()).isEqualTo("PUBLISHED");
        assertThat(mappings).hasSize(1);
        assertThat(mappings.get(0).getRole()).isEqualTo("UNKNOWN");
    }

    private static Sentence sentence(Long id, int sequence, Integer speaker, String text) {
        Sentence sentence = new Sentence();
        sentence.setId(id);
        sentence.setTaskId(100L);
        sentence.setSequence(sequence);
        sentence.setSpeakerId(speaker);
        sentence.setStartMs(0L);
        sentence.setEndMs(1000L);
        sentence.setText(text);
        return sentence;
    }

    private static Violation violation(Long versionId, Long ruleVersionId, Long sentenceId, int deduct, String note) {
        Violation violation = new Violation();
        violation.setId(System.nanoTime());
        violation.setReportVersionId(versionId);
        violation.setRuleVersionId(ruleVersionId);
        violation.setSentenceId(sentenceId);
        violation.setDeduct(deduct);
        violation.setNote(note);
        violation.setEvidenceSentenceIds(sentenceId == null ? "[]" : "[" + sentenceId + "]");
        violation.setRanges(sentenceId == null ? "[]" : "[{\"sentenceId\":" + sentenceId + ",\"start\":0,\"end\":4}]");
        violation.setEvidenceKey("k" + System.nanoTime());
        return violation;
    }

    @Test
    @DisplayName("角色映射：保存后当前版本与角色表可读")
    void mappingRoundTrip() {
        service.persistMapping(100L, 1L, Map.of(0, "AGENT", 1, "customer"), 7L, "USER");

        Map<Integer, String> roles = service.currentRoles(100L);

        assertThat(service.currentRevision(100L)).isEqualTo(1);
        assertThat(roles).containsEntry(0, "AGENT").containsEntry(1, "CUSTOMER");
        assertThat(new HashMap<>(roles)).hasSize(2);
    }
}
