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
 * - 同一 owner + 报告版本 + 选项若已有**仍有效**的产物，直接复用，不重复生成；
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
        ExportJob reusable = findReusable(ownerDomain, ownerId, reportVersionId, withAudio);
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
        List<String> summary = new ArrayList<>();
        summary.add("记录编号：" + detail.reportNo() + " · 版本 v" + detail.reportVersion()
            + "（" + detail.status() + "）");
        summary.add("命中 " + detail.flaggedCount() + " 句 · 违规 " + detail.violationCount()
            + " 项 · 风险分 " + (detail.riskScore() == null ? "未发布" : detail.riskScore())
            + "（阈值 " + detail.threshold() + "）");
        summary.add("正文中红色文字为命中原话；未标红的部分没有命中本轮启用的规则。");

        List<ReportPdfRenderer.Line> sentenceLines = new ArrayList<>();
        for (var sentence : detail.sentences()) {
            // 脱敏是等长掩码：先脱敏再按原偏移切片，标红区间不会错位
            String text = redact ? RedactionService.redact(sentence.text()) : sentence.text();
            String role = sentence.role() == null ? "未确认" : sentence.role();
            String head = "[%d] %s | ".formatted(sentence.sequence(), role);
            sentenceLines.add(ReportPdfRenderer.Line.of(hitSpans(head, text, sentence.highlights())));
        }
        List<ReportPdfRenderer.Line> evidenceLines = new ArrayList<>();
        for (var violation : detail.violations()) {
            String note = redact ? RedactionService.redact(violation.note()) : violation.note();
            evidenceLines.add(ReportPdfRenderer.Line.of(List.of(
                ReportPdfRenderer.Span.red("● "),
                ReportPdfRenderer.Span.of("%s 规则《%s》扣 %d：%s".formatted(
                    violation.matchType(), violation.ruleName(), violation.deduct(), note)))));
        }
        List<ReportPdfRenderer.Section> sections = new ArrayList<>();
        sections.add(new ReportPdfRenderer.Section("逐句判读", sentenceLines));
        sections.add(new ReportPdfRenderer.Section("命中与依据", evidenceLines));
        List<ReportPdfRenderer.Line> imageLines = imageSectionLines(assets);
        if (!imageLines.isEmpty()) {
            sections.add(new ReportPdfRenderer.Section("图片清单", imageLines));
        }
        List<String> footers = List.of(
            "本服务仅记录通话内容，不构成法律意见，",
            "不作为处理结果承诺。",
            "所引规范条文请以现行有效版本为准。");
        return new ReportPdfRenderer.PdfReport(detail.reportNo(), BusinessTime.now().toString(),
            "报告版本 v" + detail.reportVersion() + " · 规则集合 version=" + detail.reportVersionId(),
            String.join("\n", summary), sections, footers);
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
