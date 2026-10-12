package com.diting.service.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.entity.ExportJob;
import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.entity.Sentence;
import com.diting.entity.Violation;
import com.diting.mapper.ExportJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.admin.RedactionService;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PDF / ZIP 异步导出（开发文档 §9）。
 *
 * 语义要点：
 * - 创建作业只登记（QUEUED），生成由调度器推进；**GET 只查状态，不触发副作用**；
 * - 无违规项的报告可复用同 owner + 版本 + 选项的有效产物；有违规项时重新生成，
 *   因其法律依据是实时数据（即使当前未绑定，也可能随后新增绑定）；
 * - 含音频的 ZIP：原件已清理/过期直接拒绝；ZIP 有效期不晚于原件保留截止时间；
 * - 发布前复查墓碑：记录已删除时作业失败，不产出产物；
 * - 产物落对象存储，下载走短时预签名；审计只记「签发事实」，不记链接。
 */
@Slf4j
@Service
public class ExportService {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String DOMAIN_USER = "USER";
    public static final String DOMAIN_ADMIN = "ADMIN";

    private final ExportJobMapper exportJobMapper;
    private final RecognitionTaskMapper taskMapper;
    private final ReportService reportService;
    private final ReportPdfRenderer renderer;
    private final ObjectStorage objectStorage;
    private final DitingProperties props;
    private final com.diting.mapper.TaskAssetMapper taskAssetMapper;
    private final ExportLeaseService leaseService;

    public ExportService(ExportJobMapper exportJobMapper, RecognitionTaskMapper taskMapper,
                         ReportService reportService, ReportPdfRenderer renderer,
                         ObjectStorage objectStorage, com.diting.mapper.TaskAssetMapper taskAssetMapper,
                         DitingProperties props, ExportLeaseService leaseService) {
        this.exportJobMapper = exportJobMapper;
        this.taskMapper = taskMapper;
        this.reportService = reportService;
        this.renderer = renderer;
        this.objectStorage = objectStorage;
        this.taskAssetMapper = taskAssetMapper;
        this.props = props;
        this.leaseService = leaseService;
    }

    /** 创建导出作业：可复用的有效产物直接返回，否则登记新作业 */
    @Transactional
    public ExportJob create(String ownerDomain, Long ownerId, Long taskId, Long reportVersionId,
                            boolean withAudio) {
        RecognitionTask task = requireTask(taskId);
        assertVersionBelongsToTask(task, reportVersionId);
        // 法律依据不属于报告版本快照，旧 PDF/ZIP 不能凭 reportVersionId 判定内容未变。
        // 保守地禁用所有含违规项报告的复用，覆盖原文编辑、换绑、新增绑定和全部解绑。
        ExportJob reusable = reportService.violationsOf(reportVersionId).isEmpty()
            ? findReusable(ownerDomain, ownerId, reportVersionId, withAudio) : null;
        if (reusable != null) {
            return reusable;
        }
        assertOriginalsUsable(task, withAudio);

        ExportJob job = new ExportJob();
        job.setOwnerId(ownerId);
        job.setOwnerDomain(ownerDomain);
        job.setReportVersionId(reportVersionId);
        job.setWithAudio(withAudio);
        job.setStatus(STATUS_QUEUED);
        job.setExpiresAt(BusinessTime.now().plusHours(props.getExport().getArtifactTtlHours()));
        job.setAttemptCount(0);
        job.setCreatedAt(BusinessTime.now());
        exportJobMapper.insert(job);
        return job;
    }

    /** 一轮调度：领取排队作业并按顺序生成（单实例串行，避免并发打满内存） */
    public int tick() {
        // diting.export.enabled 只控制定时调度；手动调用（测试）仍然可用，与任务管线一致
        List<ExportJob> queued = exportJobMapper.selectDue(props.getExport().getBatchSize());
        int done = 0;
        for (ExportJob job : queued) {
            if (generate(job)) {
                done++;
            }
        }
        return done;
    }

    /**
     * 生成产物：墓碑复查 → 组装内容 → 渲染/打包 → 直传对象存储 → 标记完成。
     * P2 修复：OSS 上传移出 DB 事务——大文件上传时 DB 连接被长占，
     * 失败回滚还可能把“已上传一半”的对象留下。
     * 现在分三段：① CAS 领取 RUNNING+租约令牌；② 事务外渲染并上传到该令牌独占 key；
     * ③ 独立事务锁任务复查墓碑、CAS 发布。② 失败时有限退避，旧工作者不得覆盖新产物。
     */
    public boolean generate(ExportJob job) {
        Claimed claimed = claimRunning(job.getId());
        if (claimed == null) {
            return false;
        }
        String key = null;
        Path zipFile = null;
        try {
            assertOriginalsUsable(claimed.task(), Boolean.TRUE.equals(job.getWithAudio()));
            byte[] pdf = renderer.render(buildReport(claimed.task(), job, claimed.assets()));
            if (pdf.length > props.getExport().getMaxArtifactBytes()) {
                markFailed(job, claimed.token(), "ARTIFACT_TOO_LARGE", false);
                return true;
            }
            boolean withOriginals = Boolean.TRUE.equals(job.getWithAudio());
            if (withOriginals) zipFile = zip(pdf, claimed.task(), packageableImages(claimed.task()));
            LocalDateTime expires = BusinessTime.now().plusHours(props.getExport().getArtifactTtlHours());
            if (withOriginals && claimed.task().getExpiresAt() != null
                && claimed.task().getExpiresAt().isBefore(expires)) {
                expires = claimed.task().getExpiresAt();
            }
            key = objectKeyFor(job, claimed.token());
            if (withOriginals) {
                try (InputStream input = Files.newInputStream(zipFile)) {
                    objectStorage.putStream(key, input, Files.size(zipFile), "application/zip");
                }
            } else {
                objectStorage.put(key, pdf, "application/pdf");
            }
            leaseService.publish(job.getId(), claimed.token(), job.getReportVersionId(), key, expires);
            return true;
        }
        catch (Exception e) {
            log.warn("导出作业 {} 生成/发布失败：{}", job.getId(), e.getMessage());
            if (key != null) {
                try {
                    objectStorage.delete(key);
                }
                catch (Exception cleanup) {
                    log.warn("导出作业 {} 未发布产物清理失败，须由 OSS 生命周期规则回收", job.getId());
                }
            }
            boolean tooLarge = e instanceof ArtifactTooLarge;
            boolean retry = !tooLarge && (!(e instanceof BizException biz)
                || biz.getResultCode() == ResultCode.PROCESSING);
            markFailed(job, claimed.token(), tooLarge ? "ARTIFACT_TOO_LARGE"
                : e instanceof BizException biz ? biz.getResultCode().getSymbol() : "EXPORT_FAILED", retry);
            return true;
        }
        finally {
            if (zipFile != null) {
                deleteTempFile(zipFile);
            }
        }
    }

    /** 领取作业并返回 DB 快照：墓碑复查在这里做，失败直接标记 */
    private Claimed claimRunning(Long jobId) {
        String token = java.util.UUID.randomUUID().toString();
        if (!leaseService.claim(jobId, token, props.getExport().getLeaseSeconds(),
            props.getExport().getMaxAttempts())) return null;
        ExportJob job = exportJobMapper.selectById(jobId);
        if (job == null) return null;
        ReportVersion version = reportService.resolveVersionById(job.getReportVersionId());
        RecognitionTask task = version == null ? null : taskMapper.selectById(version.getTaskId());
        if (task == null || task.getDeletedAt() != null || !"PUBLISHED".equals(version.getStatus())) {
            markFailed(job, token, task == null || task.getDeletedAt() != null
                ? "TASK_DELETED" : "REPORT_NOT_PUBLISHED", false);
            return null;
        }
        return new Claimed(task, taskAssetMapper.selectByTask(task.getId()), token);
    }

    private record Claimed(RecognitionTask task, java.util.List<com.diting.entity.TaskAsset> assets,
                           String token) {
    }

    /** 取产物下载信息：只读，不改状态；过期或未完成明确报错 */
    public ExportJob requireOwnedJob(String ownerDomain, Long ownerId, Long exportId) {
        ExportJob job = exportJobMapper.selectById(exportId);
        if (job == null || !job.getOwnerDomain().equals(ownerDomain) || !job.getOwnerId().equals(ownerId)) {
            // 跨身份域、跨用户一律 404，不泄露存在性
            throw new BizException(ResultCode.NOT_FOUND, "导出作业不存在");
        }
        return job;
    }

    public String presignArtifact(ExportJob job) {
        ReportVersion version = reportService.resolveVersionById(job.getReportVersionId());
        if (version == null) {
            throw new BizException(ResultCode.NOT_FOUND, "报告版本不存在");
        }
        RecognitionTask task = requireTask(version.getTaskId());
        assertVersionBelongsToTask(task, version.getId());
        // 顺序很重要：先判「过期/已清理」再判「未完成」——过期产物对用户就是不存在
        if (job.getExpiresAt() != null && job.getExpiresAt().isBefore(BusinessTime.now())) {
            throw new BizException(ResultCode.NOT_FOUND, "导出产物已过期，请重新生成");
        }
        if (!STATUS_COMPLETED.equals(job.getStatus()) || job.getArtifactKey() == null) {
            throw new BizException(ResultCode.PROCESSING, "导出尚未完成");
        }
        // 带下载文件名：否则 PDF 会被浏览器直接内联打开，用户拿不到文件（自动下载也就形同虚设）
        return objectStorage.presignGet(job.getArtifactKey(),
            java.time.Duration.ofMinutes(props.getExport().getDownloadTtlMinutes()),
            artifactFileName(task, version, fileType(job))).url();
    }

    /**
     * 下载文件名：报告编号 + 版本 + 扩展名（如 `DT-2026-001046-v2.pdf`）。
     * 报告编号本身是 ASCII，所以处置头不需要 RFC 5987 编码。
     */
    static String artifactFileName(RecognitionTask task, ReportVersion version, String fileType) {
        long reportVersion = version == null || version.getVersion() == null ? 0L : version.getVersion();
        return "%s-v%d.%s".formatted(com.diting.dto.ReportDetailResponse.reportNo(task), reportVersion, fileType);
    }

    public String fileType(ExportJob job) {
        return Boolean.TRUE.equals(job.getWithAudio()) ? "zip" : "pdf";
    }

    /**
     * 打包原件的前置条件（S16 起同时覆盖图片）：
     * 音频或图片**任一**仍在保留期内且未清理即可打包；两者都没有则拒绝，并说明原因。
     */
    private void assertOriginalsUsable(RecognitionTask task, boolean withOriginals) {
        if (!withOriginals) {
            return;
        }
        boolean expired = task.getExpiresAt() != null && !task.getExpiresAt().isAfter(BusinessTime.now());
        if (expired) {
            throw new BizException(ResultCode.AUDIO_EXPIRED, "原件已过保留期，无法打包");
        }
        boolean audioUsable = !Boolean.TRUE.equals(task.getAudioPurged()) && task.getOssObject() != null
            && !task.getOssObject().isBlank();
        boolean imageUsable = !packageableImages(task).isEmpty();
        if (!audioUsable && !imageUsable) {
            throw new BizException(ResultCode.AUDIO_EXPIRED, "记录里已没有可打包的原件（录音或图片）");
        }
    }

    private List<com.diting.entity.TaskAsset> packageableImages(RecognitionTask task) {
        return taskAssetMapper.selectPurgeable(task.getId()).stream()
            .filter(com.diting.entity.TaskAsset::isImage)
            .filter(asset -> asset.getObjectKey() != null && !asset.getObjectKey().isBlank())
            .toList();
    }

    /**
     * 报告版本必须属于本次请求的任务：否则用户可以用自己的 taskId 配他人的自增
     * reportVersionId，导出到别人的逐句原文（withAudio 时连原始录音一起打包）。
     * 不存在或不属于该任务一律 404，不泄露存在性。
     */
    private void assertVersionBelongsToTask(RecognitionTask task, Long reportVersionId) {
        ReportVersion version = reportService.resolveVersionById(reportVersionId);
        if (version == null || !task.getId().equals(version.getTaskId())) {
            throw new BizException(ResultCode.NOT_FOUND, "报告版本不存在");
        }
        if (!"PUBLISHED".equals(version.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "报告尚未成功发布，不能导出");
        }
    }

    private ExportJob findReusable(String ownerDomain, Long ownerId, Long reportVersionId, boolean withAudio) {
        List<ExportJob> rows = exportJobMapper.selectList(new LambdaQueryWrapper<ExportJob>()
            .eq(ExportJob::getOwnerDomain, ownerDomain)
            .eq(ExportJob::getOwnerId, ownerId)
            .eq(ExportJob::getReportVersionId, reportVersionId)
            .eq(ExportJob::getWithAudio, withAudio)
            .eq(ExportJob::getStatus, STATUS_COMPLETED)
            .orderByDesc(ExportJob::getId));
        LocalDateTime now = BusinessTime.now();
        return rows.stream()
            .filter(job -> job.getExpiresAt() != null && job.getExpiresAt().isAfter(now))
            .findFirst()
            .orElse(null);
    }

    /** 组装报告内容：版本、时间、规则依据、命中标识（命中处标红）都写进 PDF */
    private ReportPdfRenderer.PdfReport buildReport(RecognitionTask task, ExportJob job,
                                                     java.util.List<com.diting.entity.TaskAsset> assets) {
        // 按 report_versions.id 取：导出引用的是版本行，不是版本号
        com.diting.entity.ReportVersion version = reportService.resolveVersionById(job.getReportVersionId());
        if (version == null) {
            throw new BizException(ResultCode.NOT_FOUND, "报告版本不存在");
        }
        var detail = reportService.detailOf(task, version);
        // 后台导出仅 ADMIN（与录音详情一致：ADMIN 看原文）；C 端本人看原文。
        // OPERATOR 调不到后台导出路由，故此处无需脱敏分支。
        boolean redact = false;
        List<ReportPdfRenderer.Span> summary = summarySpans(task, detail, assets);

        List<ReportPdfRenderer.Line> sentenceLines = new ArrayList<>();
        for (var sentence : detail.sentences()) {
            // 脱敏是等长掩码：先脱敏再按原偏移切片，标红区间不会错位
            String text = redact ? RedactionService.redact(sentence.text()) : sentence.text();
            String role = sentence.role() == null ? "未确认" : sentence.role();
            String head = "[%d] %s | ".formatted(sentence.sequence(), role);
            sentenceLines.add(ReportPdfRenderer.Line.of(hitSpans(head, text, sentence.highlights())));
        }
        List<ReportPdfRenderer.Section> sections = new ArrayList<>(violationSections(detail));
        // 原文与版本信息仍保留为附录，商务正文不再被逐句转写打断。
        sections.add(new ReportPdfRenderer.Section("逐句判读", sentenceLines));
        List<ReportPdfRenderer.Line> imageLines = imageSectionLines(assets);
        if (!imageLines.isEmpty()) {
            sections.add(new ReportPdfRenderer.Section("图片清单", imageLines));
        }
        List<String> footers = List.of("所引规范条文请以现行有效版本为准。");
        return new ReportPdfRenderer.PdfReport(detail.reportNo(), BusinessTime.now().toString(),
            "报告版本 v" + detail.reportVersion() + " · 规则集合 version=" + detail.reportVersionId(),
            summary.stream().map(ReportPdfRenderer.Span::text).collect(java.util.stream.Collectors.joining()),
            sections, footers, summary);
    }

    /** 包级可见：用真实 DTO 测试数据口径，不把模板中的示例数字当作业务数据。 */
    static List<ReportPdfRenderer.Span> summarySpans(RecognitionTask task,
        com.diting.dto.ReportDetailResponse detail, List<com.diting.entity.TaskAsset> assets) {
        List<ReportPdfRenderer.Span> spans = new ArrayList<>();
        long audioCount = assets.stream().filter(asset -> "AUDIO".equals(asset.getKind())).count();
        if (audioCount == 0 && task.getOssObject() != null && !task.getOssObject().isBlank()) audioCount = 1;
        long images = assets.stream().filter(com.diting.entity.TaskAsset::isImage).count();
        spans.add(ReportPdfRenderer.Span.of("本次检测覆盖 "));
        spans.add(ReportPdfRenderer.Span.blue(String.valueOf(audioCount)));
        spans.add(ReportPdfRenderer.Span.of(" 段通话"));
        if (images > 0) {
            spans.add(ReportPdfRenderer.Span.of("、"));
            spans.add(ReportPdfRenderer.Span.blue(String.valueOf(images)));
            spans.add(ReportPdfRenderer.Span.of(" 张图片"));
        }
        if (audioCount > 0) {
            Integer seconds = detail.durationSec();
            spans.add(ReportPdfRenderer.Span.of("、合计 "));
            spans.add(ReportPdfRenderer.Span.blue(seconds == null || seconds < 0 ? "时长未知"
                : "%02d 分 %02d 秒".formatted(seconds / 60, seconds % 60)));
        }
        long rules = detail.violations().stream().map(com.diting.dto.ViolationResponse::ruleVersionId)
            .filter(java.util.Objects::nonNull).distinct().count();
        spans.add(ReportPdfRenderer.Span.of("，共命中 "));
        spans.add(ReportPdfRenderer.Span.blue(String.valueOf(rules)));
        spans.add(ReportPdfRenderer.Span.of(" 项规则，违规 "));
        spans.add(ReportPdfRenderer.Span.blue(String.valueOf(detail.violationCount())));
        spans.add(ReportPdfRenderer.Span.of(" 项，风险分 "));
        String score = detail.riskScore() == null ? "未发布" : String.valueOf(detail.riskScore());
        boolean highRisk = detail.riskScore() != null && detail.threshold() != null
            && detail.riskScore() >= detail.threshold();
        spans.add(highRisk ? ReportPdfRenderer.Span.red(score) : ReportPdfRenderer.Span.blue(score));
        spans.add(ReportPdfRenderer.Span.of("（阈值 " + (detail.threshold() == null ? "未设置" : detail.threshold())
            + "）。上述命中项系录音内容与平台规则库的形式比对结果，不代表对任何行为合法性的认定，亦不代表行为的违法程度。"));
        return spans;
    }

    /** 每条证据生成一个违规点；依据来自规则的实时绑定，绝不推断或补造法律条文。 */
    static List<ReportPdfRenderer.Section> violationSections(com.diting.dto.ReportDetailResponse detail) {
        List<ReportPdfRenderer.Section> sections = new ArrayList<>();
        int index = 0;
        for (var violation : detail.violations()) {
            List<ReportPdfRenderer.Line> lines = new ArrayList<>();
            String type = switch (violation.matchType() == null ? "" : violation.matchType()) {
                case "hit" -> "命中关键词";
                case "must-have" -> "本通话中未出现必需表述";
                case "require" -> "触发条件后未检测到必需回应";
                default -> "规则比对";
            };
            lines.add(ReportPdfRenderer.Line.body("判定规则  " + type + "：《"
                + (violation.ruleName() == null ? "未命名规则" : violation.ruleName()) + "》"
                + "（扣分 " + (violation.deduct() == null ? "未提供" : violation.deduct()) + "）"));
            var clauses = violation.legalClauses() == null
                ? List.<com.diting.dto.ReportLegalClauseResponse>of() : violation.legalClauses();
            if (clauses.isEmpty()) {
                lines.add(new ReportPdfRenderer.Line(
                    List.of(ReportPdfRenderer.Span.of("规则依据  本规则尚未绑定法律依据。")), ReportPdfRenderer.Style.MUTED));
            }
            for (var clause : clauses) {
                String law = clause.lawName() == null ? "" : clause.lawName();
                if (!law.isBlank() && !law.startsWith("《")) law = "《" + law + "》";
                lines.add(ReportPdfRenderer.Line.body("规则依据  " + law
                    + (clause.articleNo() == null ? "" : clause.articleNo())));
                if (clause.content() != null && !clause.content().isBlank()) {
                    lines.add(ReportPdfRenderer.Line.body("条文原文  " + clause.content()));
                }
            }
            if (violation.note() != null && !violation.note().isBlank()) {
                lines.add(ReportPdfRenderer.Line.body("证据说明  " + violation.note()));
            } else {
                lines.add(new ReportPdfRenderer.Line(
                    List.of(ReportPdfRenderer.Span.of("（本违规点无附加说明）")), ReportPdfRenderer.Style.MUTED));
            }
            var ids = new java.util.LinkedHashSet<Long>();
            if (violation.sentenceId() != null) ids.add(violation.sentenceId());
            if (violation.triggerSentenceId() != null) ids.add(violation.triggerSentenceId());
            if (violation.evidenceSentenceIds() != null) ids.addAll(violation.evidenceSentenceIds());
            for (var sentence : detail.sentences()) {
                if (!ids.contains(sentence.id())) continue;
                var ranges = violation.ranges() == null ? List.<com.diting.dto.ViolationResponse.SentenceRange>of()
                    : violation.ranges().stream().filter(range -> java.util.Objects.equals(range.sentenceId(), sentence.id())).toList();
                lines.add(ReportPdfRenderer.Line.of(hitSpans("证据原话  [" + sentence.sequence() + "] ",
                    sentence.text() == null ? "" : sentence.text(), ranges)));
            }
            lines.add(ReportPdfRenderer.Line.body("建议  客观提示，不针对本次事实定性：建议保留原始录音完整文件，勿剪辑；如需评估法律后果，建议咨询执业律师。"));
            sections.add(new ReportPdfRenderer.Section("违规点 %02d".formatted(++index), lines, true));
        }
        return sections;
    }

    /**
     * 「图片清单」小节（S16）：序号、文件名、格式、大小与识别情况。
     * 只列元数据——图片原件本身在 ZIP 里，PDF 不嵌入图片。
     */
    private static List<ReportPdfRenderer.Line> imageSectionLines(
        java.util.List<com.diting.entity.TaskAsset> assets) {
        List<ReportPdfRenderer.Line> lines = new ArrayList<>();
        int index = 0;
        for (com.diting.entity.TaskAsset asset : assets) {
            if (!asset.isImage()) {
                continue;
            }
            index++;
            String ocrNote = switch (String.valueOf(asset.getOcrStatus())) {
                case "DONE" -> "已识别文字";
                case "EMPTY" -> "未识别到文字";
                case "FAILED" -> "识别失败";
                default -> "待识别";
            };
            String format = asset.getMediaFormat() == null ? "未知格式" : asset.getMediaFormat();
            lines.add(ReportPdfRenderer.Line.body("图片 %d ｜ %s ｜ %s ｜ %s ｜ %s".formatted(
                index, asset.getFilename(), format, sizeText(asset.getSizeBytes()), ocrNote)));
        }
        return lines;
    }

    private static String sizeText(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "大小未知";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + "KB";
        }
        return "%.1fMB".formatted(bytes / 1024.0 / 1024.0);
    }

    /**
     * 一句话切成「普通 + 标红」片段：标红区间来自报告版本的命中证据（UTF-16 左闭右开）。
     * 区间非法、越界或相互重叠时只做退化处理（跳过/合并），绝不越界切片。
     */
    /** 包级可见：单测直接验证切片（不需要 HTTP/DB） */
    static List<ReportPdfRenderer.Span> hitSpans(String head, String text,
                                                 List<com.diting.dto.ViolationResponse.SentenceRange> ranges) {
        List<ReportPdfRenderer.Span> spans = new ArrayList<>();
        spans.add(ReportPdfRenderer.Span.of(head));
        List<int[]> valid = new ArrayList<>();
        for (var range : ranges == null
            ? List.<com.diting.dto.ViolationResponse.SentenceRange>of() : ranges) {
            int start = Math.max(0, range.start());
            int end = Math.min(text.length(), range.end());
            if (start < end) {
                valid.add(new int[]{start, end});
            }
        }
        valid.sort(java.util.Comparator.comparingInt(range -> range[0]));
        // 合并重叠/相邻区间：命中范围取并集，否则区间一重叠就会把后半段漏标
        List<int[]> merged = new ArrayList<>();
        for (int[] range : valid) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
                continue;
            }
            merged.add(new int[]{range[0], range[1]});
        }
        int cursor = 0;
        for (int[] range : merged) {
            if (range[0] > cursor) {
                spans.add(ReportPdfRenderer.Span.of(text.substring(cursor, range[0])));
            }
            spans.add(ReportPdfRenderer.Span.red(text.substring(range[0], range[1])));
            cursor = range[1];
        }
        if (cursor < text.length()) {
            spans.add(ReportPdfRenderer.Span.of(text.substring(cursor)));
        }
        return spans;
    }

    /**
     * ZIP：报告 PDF + 原始音频（如有）+ 图片原件（S16）。
     * 目录结构固定：`report.pdf`、`audio.<ext>`、`images/<序号>-<文件名>`；
     * 某个原件已清理时跳过它，而不是让整包失败（用户在界面上已被提示「原件已清理」）。
     */
    private Path zip(byte[] pdf, RecognitionTask task, java.util.List<com.diting.entity.TaskAsset> assets) {
        boolean hasAudio = !Boolean.TRUE.equals(task.getAudioPurged())
            && task.getOssObject() != null && !task.getOssObject().isBlank();
        boolean hasImage = assets.stream().anyMatch(com.diting.entity.TaskAsset::isImage);
        if (!hasAudio && !hasImage) {
            throw new BizException(ResultCode.AUDIO_EXPIRED, "记录里已没有可打包的原件");
        }
        long limit = props.getExport().getMaxArtifactBytes();
        // 不允许压缩率极高的输入绕开原件体积预算；实际 ZIP 输出也单独限额。
        long remaining = limit - pdf.length;
        if (remaining < 0) throw artifactTooLarge();
        Path file = null;
        try {
            file = Files.createTempFile("diting-export-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new BoundedOutput(Files.newOutputStream(file), limit))) {
                int originalCount = 0;
                zip.putNextEntry(new ZipEntry("report.pdf"));
                zip.write(pdf);
                zip.closeEntry();
                if (hasAudio) {
                    try (InputStream audio = objectStorage.open(task.getOssObject())) {
                        if (audio != null) {
                            zip.putNextEntry(new ZipEntry("audio" + extensionOf(task.getOssObject())));
                            remaining = copyBounded(audio, zip, remaining);
                            zip.closeEntry();
                            originalCount++;
                        }
                    }
                }
                int index = 0;
                for (com.diting.entity.TaskAsset asset : assets) {
                    if (!asset.isImage()) {
                        continue;
                    }
                    index++;
                    if (asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
                        continue;
                    }
                    try (InputStream image = objectStorage.open(asset.getObjectKey())) {
                        if (image == null) {
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry("images/%d-%s".formatted(index, safeName(asset.getFilename()))));
                        remaining = copyBounded(image, zip, remaining);
                        zip.closeEntry();
                        originalCount++;
                    }
                }
                if (originalCount == 0) {
                    throw new BizException(ResultCode.AUDIO_EXPIRED, "原件已清理，无法生成含原件的导出包");
                }
                zip.finish();
            }
            return file;
        }
        catch (BizException e) {
            deleteTempFile(file);
            throw e;
        }
        catch (Exception e) {
            deleteTempFile(file);
            throw new BizException(ResultCode.INTERNAL_ERROR, "打包 ZIP 失败：" + e.getMessage());
        }
    }

    private static void deleteTempFile(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        }
        catch (IOException e) {
            log.warn("导出临时 ZIP 清理失败：{}", file, e);
        }
    }

    private static long copyBounded(InputStream input, ZipOutputStream zip, long remaining) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > remaining) {
                throw artifactTooLarge();
            }
            remaining -= count;
            zip.write(buffer, 0, count);
        }
        return remaining;
    }

    private static ArtifactTooLarge artifactTooLarge() {
        return new ArtifactTooLarge();
    }

    private static final class ArtifactTooLarge extends BizException {
        private ArtifactTooLarge() {
            super(ResultCode.UNPROCESSABLE, "导出内容超过体积上限");
        }
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private final long limit;
        private long count;

        private BoundedOutput(OutputStream out, long limit) {
            super(out);
            this.limit = limit;
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            if (len > limit - count) {
                throw artifactTooLarge();
            }
            out.write(bytes, off, len);
            count += len;
        }

        @Override
        public void write(int value) throws IOException {
            if (count >= limit) {
                throw artifactTooLarge();
            }
            out.write(value);
            count++;
        }
    }

    /** ZIP 内文件名只保留最后一段，避免路径穿越与奇怪字符 */
    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = slash >= 0 ? name.substring(slash + 1) : name;
        return name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private void markFailed(ExportJob job, String token, String errorCode, boolean retryable) {
        int attempted = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
        boolean retry = retryable && attempted < props.getExport().getMaxAttempts();
        leaseService.fail(job.getId(), token, errorCode, retry,
            Math.max(1, props.getExport().getRetryBackoffSeconds()) * attempted);
    }

    private RecognitionTask requireTask(Long taskId) {
        RecognitionTask task = taskMapper.selectById(taskId);
        if (task == null || task.getDeletedAt() != null) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return task;
    }

    private static String objectKeyFor(ExportJob job, String token) {
        long id = job.getId() == null ? 0L : job.getId();
        return "exports/%s/%d/%d-%s.%s".formatted(job.getOwnerDomain().toLowerCase(),
            job.getOwnerId(), id, token, Boolean.TRUE.equals(job.getWithAudio()) ? "zip" : "pdf");
    }

    private static String extensionOf(String objectKey) {
        int dot = objectKey == null ? -1 : objectKey.lastIndexOf('.');
        return dot < 0 ? "" : objectKey.substring(dot);
    }
}
