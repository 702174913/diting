package com.diting.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.common.BusinessTime;
import com.diting.common.TaskStatus;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.entity.Violation;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.ReportVersionMapper;
import com.diting.mapper.ViolationMapper;
import com.diting.service.ConfigService;
import com.diting.service.TaskPipelineService;
import com.diting.service.rules.RuleMatchService;
import com.diting.service.rules.RuleModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告分析与发布（开发文档 §5.2、§6.3、§8）。
 *
 * 原子性：命中证据与报告版本在**同一事务**里写入，状态从 ANALYZING 走到 PUBLISHED 才算完成；
 * 任务置 COMPLETED 也必须发生在已发布报告之后（复用 S06 的守卫）。
 *
 * 失败语义：分析失败时事务整体回滚（不会留下半份新报告）。历史 FAILED 版本仍可读，
 * 但当前流程不伪造「已记录失败版本」的承诺；请求失败由 API 返回错误，用户可重试。
 */
@Service
public class ReportAnalysisService {

    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** 兼容历史 FAILED 数据；当前发布流程只持久化已完成的 PUBLISHED 版本。 */
    public static final String STATUS_FAILED = "FAILED";

    private final ReportVersionMapper reportVersionMapper;
    private final ViolationMapper violationMapper;
    private final RuleMatchService engine;
    private final ConfigService configService;
    private final TaskPipelineService pipeline;
    private final RecognitionTaskMapper taskMapper;
    private final ObjectMapper json;

    public ReportAnalysisService(ReportVersionMapper reportVersionMapper, ViolationMapper violationMapper,
                                 RuleMatchService engine, ConfigService configService,
                                 TaskPipelineService pipeline, RecognitionTaskMapper taskMapper, ObjectMapper json) {
        this.reportVersionMapper = reportVersionMapper;
        this.violationMapper = violationMapper;
        this.engine = engine;
        this.configService = configService;
        this.pipeline = pipeline;
        this.taskMapper = taskMapper;
        this.json = json;
    }

    /** 发布一个新版本：写报告版本 + 命中证据，发布后再推进任务状态 */
    @Transactional
    public ReportVersion publish(RecognitionTask task, long ruleSetVersion, long mappingRevision,
                                 List<RuleModels.RuleDefinition> rules,
                                 List<RuleModels.SentenceView> sentences,
                                 java.util.Map<Integer, String> roles, boolean rolesConfirmed) {
        // 锁保持到发布事务结束：删除先发生则拒绝，发布先发生则删除等待并能清理完整版本。
        RecognitionTask live = requireLiveSnapshot(task);
        TaskStatus current = TaskStatus.fromLegacy(live.getStatus());
        boolean allowed = rolesConfirmed
            ? current == TaskStatus.WAITING_ROLE || current == TaskStatus.COMPLETED
            : current == TaskStatus.PARSING || current == TaskStatus.WAITING_ROLE
                || current == TaskStatus.ANALYZING
                // 未确认角色的重判：已完成的记录也要能按新规则集再算一版（后台「重新判读」）
                || current == TaskStatus.COMPLETED;
        if (!allowed) {
            throw new BizException(ResultCode.CONFLICT, "当前任务状态不允许发布报告");
        }
        if (sentences == null || sentences.stream().noneMatch(s -> s.text() != null && !s.text().isBlank())) {
            throw new BizException(ResultCode.UNPROCESSABLE, "没有可判读的文本，不能发布空报告");
        }
        if (current != TaskStatus.ANALYZING && !pipeline.markAnalyzing(live)) {
            throw new BizException(ResultCode.CONFLICT, "任务状态已变化，请刷新后重试");
        }
        ConfigService.PolicyConfig config = configService.load();
        RuleModels.AnalysisResult result = engine.analyze(sentences, roles, rolesConfirmed, rules);

        int nextVersion = (int) (maxVersion(task.getId()) + 1);
        LocalDateTime now = BusinessTime.now();
        ReportVersion version = new ReportVersion();
        version.setTaskId(task.getId());
        version.setVersion((long) nextVersion);
        version.setRuleSetVersion(ruleSetVersion);
        version.setMappingRevision(mappingRevision);
        version.setThresholdSnapshot(config.riskThreshold());
        version.setConfigVersion(config.version());
        version.setStatus(STATUS_ANALYZING);
        version.setCreatedAt(now);
        reportVersionMapper.insert(version);

        for (RuleModels.ViolationItem item : result.violations()) {
            Violation row = new Violation();
            row.setReportVersionId(version.getId());
            row.setRuleVersionId(item.ruleVersionId());
            row.setSentenceId(item.sentenceId());
            row.setTriggerSentenceId(item.triggerSentenceId());
            row.setEvidenceSentenceIds(toJson(item.evidenceSentenceIds()));
            row.setRanges(toJson(item.ranges().stream()
                .map(range -> new RangePayload(range.sentenceId(), range.start(), range.end()))
                .toList()));
            row.setDeduct(item.deduct());
            row.setNote(item.note());
            row.setEvidenceKey(evidenceKey(item));
            violationMapper.insert(row);
        }

        version.setRiskScore(result.riskScore());
        version.setComplianceScore(result.complianceScore());
        version.setStatus(STATUS_PUBLISHED);
        version.setPublishedAt(now);
        reportVersionMapper.updateById(version);

        // 完成 CAS 必须成功；失败抛异常，报告与证据及进入 ANALYZING 的更新一起回滚。
        pipeline.markCompleted(live, true);
        return version;
    }

    private RecognitionTask requireLiveSnapshot(RecognitionTask snapshot) {
        RecognitionTask live = taskMapper.lockById(snapshot.getId());
        if (live == null || live.getDeletedAt() != null
            || TaskStatus.DELETING.name().equals(live.getStatus())
            || TaskStatus.DELETED.name().equals(live.getStatus())) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在或已删除");
        }
        if (!java.util.Objects.equals(live.getVersion(), snapshot.getVersion())) {
            throw new BizException(ResultCode.CONFLICT, "任务已被更新，请刷新后重试");
        }
        return live;
    }

    /** 最新版本号（无版本时为 0） */
    public long maxVersion(Long taskId) {
        List<ReportVersion> rows = reportVersionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId)
            .orderByDesc(ReportVersion::getVersion)
            .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getVersion() == null ? 0L : rows.get(0).getVersion();
    }

    /** 证据去重键：同规则同证据只记一次（uk: report_version_id + rule_version_id + evidence_key） */
    private static String evidenceKey(RuleModels.ViolationItem item) {
        StringBuilder key = new StringBuilder();
        key.append(item.sentenceId() == null ? "CALL" : "S" + item.sentenceId());
        key.append('-').append(item.triggerSentenceId() == null ? "-" : item.triggerSentenceId());
        key.append('-').append(String.join(".", item.evidenceSentenceIds().stream().map(String::valueOf).toList()));
        key.append('-').append(item.ranges().size());
        String value = key.toString();
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new IllegalStateException("报告字段序列化失败", e);
        }
    }

    /** 命中区间落库结构（源码 UTF-16、左闭右开） */
    public record RangePayload(Long sentenceId, int start, int end) {
    }
}
