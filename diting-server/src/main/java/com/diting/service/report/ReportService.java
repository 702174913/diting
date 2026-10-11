package com.diting.service.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
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
import com.diting.service.rules.LegalClauseService;
import com.diting.service.rules.RuleMatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diting.service.rules.RuleModels;
import com.diting.service.rules.RuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 版本化报告（开发文档 §5.2、§7.2、§8）。
 *
 * 关键口径：
 * - 听悟 SpeakerId 只区分说话人，不能推断催收方/客户；新任务不要求角色确认，直接发布报告；
 * - 历史角色映射与报告版本保持可读，不覆盖旧版本；
 * - 版本号只增不减：重算不会覆盖旧版本，旧版本始终可读（可复算）；
 * - `expectedRevision` 冲突返回 409，避免两个人同时改角色把对方覆盖掉。
 */
@Slf4j
@Service
public class ReportService {

    private final SpeakerMappingMapper speakerMappingMapper;
    private final ReportVersionMapper reportVersionMapper;
    private final ViolationMapper violationMapper;
    private final SentenceMapper sentenceMapper;
    private final RuleService ruleService;
    private final LegalClauseService legalClauseService;
    private final ReportAnalysisService analysisService;
    private final RecognitionTaskMapper taskMapper;
    private final ObjectMapper json;

    public ReportService(SpeakerMappingMapper speakerMappingMapper, ReportVersionMapper reportVersionMapper,
                         ViolationMapper violationMapper, SentenceMapper sentenceMapper,
                         RuleService ruleService, LegalClauseService legalClauseService,
                         ReportAnalysisService analysisService,
                         RecognitionTaskMapper taskMapper, ObjectMapper json) {
        this.speakerMappingMapper = speakerMappingMapper;
        this.reportVersionMapper = reportVersionMapper;
        this.violationMapper = violationMapper;
        this.sentenceMapper = sentenceMapper;
        this.ruleService = ruleService;
        this.legalClauseService = legalClauseService;
        this.analysisService = analysisService;
        this.taskMapper = taskMapper;
        this.json = json;
    }

    /** 映射与报告同事务；任何失败整体回滚，客户端可用原 revision 重试，不留下半份映射。 */
    @Transactional
    public SpeakerMapOutcome saveMapping(Long actorId, String actorDomain, RecognitionTask task,
                                         long expectedRevision, Map<Integer, String> mapping) {
        RecognitionTask live = taskMapper.lockById(task.getId());
        if (live == null || live.getDeletedAt() != null) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在或已删除");
        }
        TaskStatus status = TaskStatus.fromLegacy(live.getStatus());
        if (status != TaskStatus.WAITING_ROLE && status != TaskStatus.COMPLETED) {
            throw new BizException(ResultCode.CONFLICT, "请等待识别完成后再确认角色");
        }
        long currentRevision = currentRevision(live.getId());
        if (expectedRevision != currentRevision) {
            throw new BizException(ResultCode.CONFLICT,
                "角色映射已被更新（当前版本 " + currentRevision + "），请刷新后重试");
        }
        if (mapping == null || mapping.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "角色映射不能为空");
        }
        List<Sentence> sentences = sentencesOf(live.getId());
        java.util.Set<Integer> speakers = sentences.stream()
            .filter(s -> s.getSourceAssetId() == null)
            .map(Sentence::getSpeakerId).filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (speakers.isEmpty() || !speakers.equals(mapping.keySet())) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请确认本记录全部真实说话人的角色，不能添加或遗漏说话人");
        }
        Map<Integer, String> normalized = normalizeMapping(mapping);
        long nextRevision = currentRevision + 1;
        persistMapping(live.getId(), nextRevision, normalized, actorId, actorDomain);
        ReportVersion version = analyze(live, nextRevision, true);
        return new SpeakerMapOutcome(nextRevision, version.getVersion(), version.getStatus());
    }

    /** 角色确认后的重新分析（也可用于重新冻结规则集合后的重算） */
    public ReportVersion analyze(RecognitionTask task, long mappingRevision, boolean rolesConfirmed) {
        RuleService.RuleSetSnapshot ruleSet = ruleService.snapshotEnabledRules();
        List<Sentence> sentences = sentencesOf(task.getId());
        Map<Integer, String> roles = rolesAt(task.getId(), mappingRevision);
        List<RuleModels.SentenceView> views = sentences.stream()
            .map(s -> new RuleModels.SentenceView(s.getId(), s.getSequence() == null ? 0 : s.getSequence(),
                s.getSpeakerId(), s.getText()))
            .toList();
        return analysisService.publish(task, ruleSet.ruleSetVersion(), mappingRevision, ruleSet.rules(),
            views, roles, rolesConfirmed);
    }

    /** 仅用原始 SpeakerId 发布报告；无法判断业务角色的规则会被跳过并给出说明。 */
    public ReportVersion analyzeWithoutRoles(RecognitionTask task) {
        return analyze(task, currentRevision(task.getId()), false);
    }

    @Transactional
    public void persistMapping(Long taskId, long revision, Map<Integer, String> mapping,
                               Long actorId, String actorDomain) {
        Map<Integer, String> normalized = normalizeMapping(mapping);
        LocalDateTime now = BusinessTime.now();
        for (Map.Entry<Integer, String> entry : normalized.entrySet()) {
            String role = entry.getValue();
            SpeakerMapping row = new SpeakerMapping();
            row.setTaskId(taskId);
            row.setSpeakerId(entry.getKey());
            row.setRole(role);
            row.setRevision(revision);
            row.setConfirmedBy(actorId);
            row.setConfirmedDomain(actorDomain);
            row.setConfirmedAt(now);
            speakerMappingMapper.insert(row);
        }
    }

    private static Map<Integer, String> normalizeMapping(Map<Integer, String> mapping) {
        Map<Integer, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
            if (entry.getKey() == null) {
                throw new BizException(ResultCode.UNPROCESSABLE, "speakerId 不能为空");
            }
            String role = entry.getValue() == null ? "UNKNOWN" : entry.getValue().trim().toUpperCase(Locale.ROOT);
            if (!List.of("AGENT", "CUSTOMER", "UNKNOWN").contains(role)) {
                throw new BizException(ResultCode.UNPROCESSABLE, "角色只能是 AGENT/CUSTOMER/UNKNOWN");
            }
            normalized.put(entry.getKey(), role);
        }
        return normalized;
    }

    public long currentRevision(Long taskId) {
        List<SpeakerMapping> rows = speakerMappingMapper.selectList(new LambdaQueryWrapper<SpeakerMapping>()
            .eq(SpeakerMapping::getTaskId, taskId)
            .orderByDesc(SpeakerMapping::getRevision)
            .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getRevision() == null ? 0L : rows.get(0).getRevision();
    }

    /** 当前角色映射：speakerId → role */
    public Map<Integer, String> currentRoles(Long taskId) {
        return rolesAt(taskId, currentRevision(taskId));
    }

    public Map<Integer, String> rolesAt(Long taskId, long revision) {
        if (revision == 0) {
            return Map.of();
        }
        List<SpeakerMapping> rows = speakerMappingMapper.selectList(new LambdaQueryWrapper<SpeakerMapping>()
            .eq(SpeakerMapping::getTaskId, taskId)
            .eq(SpeakerMapping::getRevision, revision));
        Map<Integer, String> roles = new LinkedHashMap<>();
        for (SpeakerMapping row : rows) {
            roles.put(row.getSpeakerId(), row.getRole());
        }
        return roles;
    }

    public List<Sentence> sentencesOf(Long taskId) {
        return sentenceMapper.selectList(new LambdaQueryWrapper<Sentence>()
            .eq(Sentence::getTaskId, taskId)
            .orderByAsc(Sentence::getSequence));
    }

    /** 取要展示的报告版本：指定版本优先，否则取最新已发布，再退回最新一条（可能是失败的） */
    public ReportVersion resolveVersion(Long taskId, Long requestedVersion) {
        if (requestedVersion != null) {
            ReportVersion version = reportVersionMapper.selectOne(new LambdaQueryWrapper<ReportVersion>()
                .eq(ReportVersion::getTaskId, taskId)
                .eq(ReportVersion::getVersion, requestedVersion));
            if (version == null) {
                throw new BizException(ResultCode.NOT_FOUND, "报告版本不存在");
            }
            return version;
        }
        List<ReportVersion> published = reportVersionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId)
            .eq(ReportVersion::getStatus, ReportAnalysisService.STATUS_PUBLISHED)
            .orderByDesc(ReportVersion::getVersion)
            .last("LIMIT 1"));
        if (!published.isEmpty()) {
            return published.get(0);
        }
        List<ReportVersion> latest = reportVersionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId)
            .orderByDesc(ReportVersion::getVersion)
            .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0);
    }

    /** 按报告版本 id 取版本（导出按版本引用时用） */
    public ReportVersion resolveVersionById(Long reportVersionId) {
        return reportVersionId == null ? null : reportVersionMapper.selectById(reportVersionId);
    }

    /** 最新一条版本的执行状态（用于界面提示「最新一次重算失败」） */
    public String latestStatus(Long taskId) {
        List<ReportVersion> latest = reportVersionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
            .eq(ReportVersion::getTaskId, taskId)
            .orderByDesc(ReportVersion::getVersion)
            .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0).getStatus();
    }

    public List<Violation> violationsOf(Long reportVersionId) {
        return violationMapper.selectList(new LambdaQueryWrapper<Violation>()
            .eq(Violation::getReportVersionId, reportVersionId)
            .orderByAsc(Violation::getId));
    }

    /** 统计：flaggedCount 按命中**句子**去重，violationCount 含通话级缺失项 */
    public static int[] counts(List<Violation> violations) {
        List<Long> flaggedSentences = new ArrayList<>();
        for (Violation item : violations) {
            if (item.getSentenceId() != null && !flaggedSentences.contains(item.getSentenceId())) {
                flaggedSentences.add(item.getSentenceId());
            }
        }
        return new int[]{flaggedSentences.size(), violations.size()};
    }

    /**
     * 组装成一份可展示的质检记录（含逐句高亮、计数与分数口径）。
     * 注意：入参 requestedVersion 是**版本号**（1、2、3…），不是 report_versions.id。
     * 需要按行 id 取用 {@link #detailOf(RecognitionTask, ReportVersion)}。
     */
    public com.diting.dto.ReportDetailResponse detail(RecognitionTask task, Long requestedVersion) {
        return detailOf(task, resolveVersion(task.getId(), requestedVersion));
    }

    /** 按指定版本行组装（导出按 reportVersionId 引用时必须走这里） */
    public com.diting.dto.ReportDetailResponse detailOf(RecognitionTask task, ReportVersion version) {
        List<Violation> violations = version == null ? List.of() : violationsOf(version.getId());
        Map<Long, RuleService.RuleVersionInfo> ruleInfo = version == null ? Map.of()
            : ruleService.ruleVersions(violations.stream().map(Violation::getRuleVersionId).distinct().toList());
        // 法律条款按规则实时读取（V13）：绑定改了，历史报告重读时展示的是当前绑定
        Map<Long, List<com.diting.dto.LegalClauseResponse>> clauseInfo = version == null ? Map.of()
            : legalClauseService.clausesByRuleVersionIds(
                violations.stream().map(Violation::getRuleVersionId).distinct().toList());
        Map<Integer, String> roles = version == null ? currentRoles(task.getId())
            : rolesAt(task.getId(), version.getMappingRevision());
        boolean rolesConfirmed = !roles.isEmpty();
        boolean published = version != null && ReportAnalysisService.STATUS_PUBLISHED.equals(version.getStatus());

        // 逐句聚合高亮/依据/说明
        Map<Long, List<com.diting.dto.ViolationResponse.SentenceRange>> rangesBySentence = new LinkedHashMap<>();
        Map<Long, List<Long>> rulesBySentence = new LinkedHashMap<>();
        Map<Long, List<String>> notesBySentence = new LinkedHashMap<>();
        List<com.diting.dto.ViolationResponse> violationViews = new ArrayList<>();
        for (Violation item : violations) {
            List<com.diting.dto.ViolationResponse.SentenceRange> ranges = readJson(item.getRanges(),
                new com.fasterxml.jackson.core.type.TypeReference<List<com.diting.dto.ViolationResponse.SentenceRange>>() {
                });
            RuleService.RuleVersionInfo info = ruleInfo.get(item.getRuleVersionId());
            violationViews.add(new com.diting.dto.ViolationResponse(item.getId(), item.getRuleVersionId(),
                info == null ? null : info.name(),
                info == null ? null : info.matchType(),
                info == null ? null : info.roleScope(),
                item.getSentenceId(), item.getTriggerSentenceId(),
                readLongs(item.getEvidenceSentenceIds()), ranges, item.getDeduct(), item.getNote(),
                clauseInfo.getOrDefault(item.getRuleVersionId(), List.of())));
            for (com.diting.dto.ViolationResponse.SentenceRange range : ranges) {
                if (range.sentenceId() == null) {
                    continue;
                }
                rangesBySentence.computeIfAbsent(range.sentenceId(), k -> new ArrayList<>()).add(range);
                rulesBySentence.computeIfAbsent(range.sentenceId(), k -> new ArrayList<>())
                    .add(item.getRuleVersionId());
                notesBySentence.computeIfAbsent(range.sentenceId(), k -> new ArrayList<>()).add(item.getNote());
            }
        }

        List<com.diting.dto.SentenceResponse> sentenceViews = sentencesOf(task.getId()).stream()
            .map(s -> com.diting.dto.SentenceResponse.of(s,
                s.getSpeakerId() == null ? null : roles.get(s.getSpeakerId()),
                rangesBySentence.getOrDefault(s.getId(), List.of()),
                rulesBySentence.getOrDefault(s.getId(), List.of()),
                notesBySentence.getOrDefault(s.getId(), List.of())))
            .toList();

        int[] counts = counts(violations);
        boolean hasAudio = com.diting.common.MediaKind.hasAudio(task.getMediaKind());
        boolean audioAvailable = hasAudio && !Boolean.TRUE.equals(task.getAudioPurged())
            && (task.getExpiresAt() == null || task.getExpiresAt().isAfter(BusinessTime.now()));
        // P2 修复：纯图片任务本来就没有录音，audioAvailable=false 是常态而非异常；
        // 文案由调用方按 hasAudio 区分（见 detailOf 末尾 notes），这里只保留事实判断。
        Integer riskScore = published ? version.getRiskScore() : null;
        Integer compliance = published ? version.getComplianceScore() : null;
        Integer threshold = version == null ? null : version.getThresholdSnapshot();
        boolean riskFlagged = riskScore != null && threshold != null
            && RuleMatchService.isRisky(riskScore, threshold);

        List<com.diting.dto.ReportDetailResponse.SpeakerRole> mapping = roles.entrySet().stream()
            .map(e -> new com.diting.dto.ReportDetailResponse.SpeakerRole(e.getKey(), e.getValue()))
            .toList();
        List<String> notes = new ArrayList<>();
        String latest = latestStatus(task.getId());
        if (latest != null && !ReportAnalysisService.STATUS_PUBLISHED.equals(latest)) {
            String stateLabel = ReportAnalysisService.STATUS_FAILED.equals(latest) ? "失败" : "尚未完成";
            notes.add("最新一次判读" + stateLabel + (published
                ? "，当前展示已发布的第 " + version.getVersion() + " 版" : "，当前没有可用的已发布报告"));
        }
        if (!rolesConfirmed) {
            notes.add(RuleMatchService.ROLE_TARGETING_HIDDEN
                ? "目标角色功能暂已隐藏：本次判读不区分说话人，全部规则按“任意角色”处理"
                : "按听悟 SpeakerId 区分说话人，未推断催收方或客户；业务角色限定规则未参与判读");
        }
        if (!audioAvailable) {
            // 纯图片任务本来就没有录音：不提示“已过保留期”，避免误导用户以为丢了东西
            notes.add(hasAudio ? "原始录音已过保留期或已删除，文本与报告仍按保留策略可读"
                : "本记录为纯图片任务，没有原始录音，文字与判读结果仍按保留策略可读");
        }
        return new com.diting.dto.ReportDetailResponse(
            task.getId(), task.getTaskId(), com.diting.dto.ReportDetailResponse.reportNo(task),
            task.getStatus(), task.getDurationSec(),
            task.getCreatedAt() == null ? "" : task.getCreatedAt().toString(),
            version == null ? null : version.getId(),
            version == null ? null : version.getVersion().intValue(),
            version == null ? null : version.getStatus(),
            latest,
            version == null ? 0L : version.getMappingRevision(),
            mapping, rolesConfirmed,
            sentenceViews.size(), counts[0], counts[1],
            riskScore, compliance, threshold, riskFlagged,
            version == null || version.getPublishedAt() == null ? null : version.getPublishedAt().toString(),
            audioAvailable, false, notes, sentenceViews, violationViews);
    }

    private List<Long> readLongs(String value) {
        return readJson(value, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {
        });
    }

    private <T> List<T> readJson(String value, com.fasterxml.jackson.core.type.TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, type);
        }
        catch (Exception e) {
            return List.of();
        }
    }

    public record SpeakerMapOutcome(long mappingRevision, long reportVersion, String analysisStatus) {
    }
}
