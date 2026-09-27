package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.MediaKind;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.entity.TaskAsset;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.ocr.OcrApiException;
import com.diting.service.ocr.OcrParseException;
import com.diting.service.ocr.OcrParser;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import com.diting.service.tingwu.TranscriptionParser;
import com.diting.service.tingwu.CloudSubmitRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 可恢复后台任务管线（开发文档 §5.2、§5.3）。
 *
 * 关键性质：
 * - **租约 + 版本 CAS 领取**：同一任务在任一时刻只有一个工作者能推进；进程崩溃后租约过期可被重领；
 * - **状态只走合法迁移**，未知/非法转换直接放弃本轮，不做“尽量改”；
 * - **提交不确定性不盲重提**：CreateTask 超时按不确定处理，有限次数内退避重试，超限标记
 *   `SUBMIT_UNRECONCILED` 并保留额度预占等待人工对账；
 * - **额度策略**：云端确认受理才转已消费；确定未被受理才退还；不确定既退还也不重提；
 * - **墓碑优先**：删除中的任务不再被推进，迟到回调也不会复活；
 * - **COMPLETED 必须有已发布报告**：没有报告发布者时不允许把任务标成完成。
 */
@Slf4j
@Service
public class TaskPipelineService {

    /** 错误码：可定位、可告警，不写敏感响应 */
    public static final String ERR_SUBMIT_FAILED = "SUBMIT_FAILED";
    public static final String ERR_SUBMIT_REJECTED = "SUBMIT_REJECTED";
    public static final String ERR_SUBMIT_UNCERTAIN = "SUBMIT_UNCERTAIN";
    public static final String ERR_SUBMIT_UNRECONCILED = "SUBMIT_UNRECONCILED";
    public static final String ERR_QUOTA_UNSETTLED = "QUOTA_UNSETTLED";
    public static final String ERR_REMOTE_FAILED = "REMOTE_FAILED";
    public static final String ERR_REMOTE_UNKNOWN = "REMOTE_UNKNOWN";
    public static final String ERR_PARSE_FAILED = "PARSE_FAILED";
    public static final String ERR_NO_REPORT = "NO_PUBLISHED_REPORT";
    public static final String ERR_OCR_FAILED = "OCR_FAILED";
    /** OCR 被云端明确拒绝（未开通/无权限/图片本身不合法）：不再自动重试 */
    public static final String ERR_OCR_REJECTED = "OCR_REJECTED";
    public static final String ERR_NO_TEXT = "NO_USABLE_TEXT";

    private final RecognitionTaskMapper taskMapper;
    private final SentenceMapper sentenceMapper;
    private final TingwuService tingwuService;
    private final ConfigService configService;
    private final DitingProperties props;
    private final OcrService ocrService;
    private final TaskAssetMapper taskAssetMapper;
    private final ObjectStorage objectStorage;
    /** 报告发布要回调本类（markAnalyzing/markCompleted），这里必须 @Lazy 才能打断循环依赖 */
    private final ReportService reportService;
    private final TaskTextWriter textWriter;
    private final TaskOcrWriter ocrWriter;
    private final TaskQuotaSettlement settlement;

    public TaskPipelineService(RecognitionTaskMapper taskMapper, SentenceMapper sentenceMapper,
                               TingwuService tingwuService,
                               ConfigService configService, DitingProperties props,
                               OcrService ocrService, TaskAssetMapper taskAssetMapper,
                               ObjectStorage objectStorage,
                               @Lazy ReportService reportService, TaskTextWriter textWriter,
                               TaskOcrWriter ocrWriter, TaskQuotaSettlement settlement) {
        this.taskMapper = taskMapper;
        this.sentenceMapper = sentenceMapper;
        this.tingwuService = tingwuService;
        this.configService = configService;
        this.props = props;
        this.ocrService = ocrService;
        this.taskAssetMapper = taskAssetMapper;
        this.objectStorage = objectStorage;
        this.reportService = reportService;
        this.textWriter = textWriter;
        this.ocrWriter = ocrWriter;
        this.settlement = settlement;
    }

    /** 一轮调度：领取到期任务并按状态推进，返回本轮实际推进的任务数 */
    public int tick() {
        int progressed = 0;
        for (RecognitionTask candidate : taskMapper.selectDue(props.getPipeline().getBatchSize())) {
            if (advanceOne(candidate)) {
                progressed++;
            }
        }
        return progressed;
    }

    private boolean advanceOne(RecognitionTask snapshot) {
        TaskStatus current = TaskStatus.fromLegacy(snapshot.getStatus());
        if (current == null || !current.needsWorker() || snapshot.getDeletedAt() != null) {
            return false;
        }
        boolean hasAudio = MediaKind.hasAudio(snapshot.getMediaKind());
        // 历史 WAITING_ROLE 只需用已落库文本生成报告，不再访问云服务。
        if (current != TaskStatus.WAITING_ROLE && hasAudio && !tingwuService.isConfigured()) {
            return false;
        }
        if (current != TaskStatus.WAITING_ROLE && !hasAudio && !ocrService.isConfigured()) {
            return false;
        }
        TaskStatus work = switch (current) {
            // 状态机只允许 QUEUED → SUBMITTING：图片任务没有云端提交，SUBMITTING 在这里表示「已登记，准备识别」
            case QUEUED -> TaskStatus.SUBMITTING;
            case SUBMITTING, TRANSCRIBING, PARSING, WAITING_ROLE -> current;
            default -> null;
        };
        // 领取要么是「同状态续租」（正在处理的中间态再领一次），要么是合法迁移（QUEUED → SUBMITTING/TRANSCRIBING）
        if (work == null || (work != current && !current.canTransitionTo(work))) {
            return false;
        }
        long version = snapshot.getVersion() == null ? 0L : snapshot.getVersion();
        LocalDateTime leaseUntil = BusinessTime.now().plusSeconds(props.getPipeline().getLeaseSeconds());
        if (taskMapper.claim(snapshot.getId(), version, work.name(), leaseUntil) == 0) {
            // 被别的实例领走了
            return false;
        }
        snapshot.setVersion(version + 1);

        try {
            return switch (work) {
                case SUBMITTING -> !hasAudio
                    // 图片任务：没有云端提交这一步，直接进入识别态（合法迁移 SUBMITTING → TRANSCRIBING）
                    ? backoff(snapshot, TaskStatus.TRANSCRIBING, null, null)
                    : (current == TaskStatus.QUEUED ? submitToCloud(snapshot) : reconcileSubmit(snapshot));
                case TRANSCRIBING -> hasAudio && snapshot.getTaskId() != null
                    ? pollRemote(snapshot)
                    : finishOcr(snapshot, TaskStatus.TRANSCRIBING, false);
                case PARSING -> hasAudio
                    ? parseAndPersist(snapshot)
                    : analyzeImageTask(snapshot);
                case WAITING_ROLE -> publishWithoutRoles(snapshot);
                default -> false;
            };
        }
        catch (BizException e) {
            // 业务异常（含未配置、解析失败）走退避，不吞掉
            backoff(snapshot, work, ERR_PARSE_FAILED, e.getMessage());
            return true;
        }
        catch (Exception e) {
            log.warn("任务 {} 推进失败：{}", snapshot.getId(), e.getMessage());
            backoff(snapshot, work, ERR_SUBMIT_FAILED, "内部错误，已安排重试");
            return true;
        }
    }

    /** OCR 的走向：处理完 / 本轮失败需重试 / 停在这里（未配置，不算失败）/ 次数用尽 / 云端明确拒绝 */
    private enum OcrOutcome {
        DONE, RETRY, STOP, EXHAUSTED, REJECTED
    }

    /**
     * 一轮 OCR 扫描的结果：走向 + 失败标记。
     * 单独带一个标记而不是只用枚举，是因为「为什么失败」必须留在资产行与任务上：
     * 以前不管什么失败都只记常量 INTERNAL_ERROR，线上分不清「服务没开通」和「图坏了」。
     */
    private record OcrScan(OcrOutcome outcome, String failureMark) {
        static OcrScan of(OcrOutcome outcome) {
            return new OcrScan(outcome, null);
        }
    }

    /**
     * OCR 收尾：把这一轮识别结果落库，然后按任务类型决定下一步。
     * - 纯图片：TRANSCRIBING → PARSING（下一轮在 PARSING 里发布报告并完成）；
     * - 含音频：音频已解析、图片刚识别完，直接按听悟 SpeakerId 发布报告。
     */
    private boolean finishOcr(RecognitionTask task, TaskStatus stayState, boolean hasAudioText) {
        OcrScan scan = ocrPendingImages(task);
        return switch (scan.outcome()) {
            case DONE -> {
                if (hasAudioText) {
                    yield publishWithoutRoles(task);
                }
                // 空音频不意味着混合任务无效：图片仍须 OCR，有文字则按无角色报告发布。
                // 先持久进入 PARSING，确保自动分析只从合法状态触发，并可在失败后重领。
                if (!TaskStatus.PARSING.name().equals(task.getStatus())) {
                    yield backoff(task, TaskStatus.PARSING, null, null);
                }
                yield analyzeImageTask(task);
            }
            case RETRY -> backoff(task, stayState, ERR_OCR_FAILED, "图片识别失败，稍后重试");
            case STOP -> false;
            case EXHAUSTED -> backoff(task, TaskStatus.FAILED, ERR_OCR_FAILED,
                "图片识别多次失败，已停止重试，请联系管理员处理");
            case REJECTED -> backoff(task, TaskStatus.FAILED, ERR_OCR_REJECTED,
                "图片识别被云端拒绝（" + scan.failureMark() + "），已停止重试："
                    + "请检查「通用文字识别」是否开通、RAM 是否已授 AliyunOCRFullAccess，或换一张图片重新提交");
        };
    }

    /**
     * 识别待处理的图片（PENDING/FAILED/RUNNING 且租约过期）。
     * 逐张推进：成功即写回识别结果并刷新句子；失败按**原因**分流——
     * 网络中断/限流/云端 5xx 等有限重试；云端明确拒绝（未开通、无权限、图片本身不合法）当次即置终态，
     * 不再拿同一张图反复付费重试。
     */
    private OcrScan ocrPendingImages(RecognitionTask task) {
        if (!ocrService.isConfigured()) {
            // 没有 OCR 凭据：不动状态、不判失败，等配置好再继续（与听悟未配置一致）
            return OcrScan.of(OcrOutcome.STOP);
        }
        List<TaskAsset> pending = taskAssetMapper.selectImagesToOcr(task.getId());
        int maxAttempts = Math.max(1, props.getPipeline().getMaxAttempts());
        if (pending.isEmpty()) {
            return OcrScan.of(taskAssetMapper.countExhaustedOcr(task.getId()) > 0
                ? OcrOutcome.EXHAUSTED : OcrOutcome.DONE);
        }
        boolean allDone = true;
        for (TaskAsset asset : pending) {
            int attempts = asset.getOcrAttempts() == null ? 0 : asset.getOcrAttempts();
            if (attempts >= maxAttempts) {
                if (ocrWriter.exhaust(task, asset.getId(), maxAttempts)) {
                    return OcrScan.of(OcrOutcome.EXHAUSTED);
                }
                allDone = false; // another worker still holds an unexpired lease
                continue;
            }
            String token = java.util.UUID.randomUUID().toString();
            if (!ocrWriter.claim(task, asset.getId(), token, props.getPipeline().getLeaseSeconds(), maxAttempts)) {
                allDone = false;
                continue;
            }
            OcrParser.ParsedOcr parsed;
            long startedAt = System.nanoTime();
            try {
                byte[] bytes = objectStorage.read(asset.getObjectKey());
                if (bytes == null) {
                    ocrWriter.finish(task, asset.getId(), token, TaskAsset.OCR_FAILED, null,
                        TaskAsset.OCR_ERROR_OBJECT_MISSING);
                    return OcrScan.of(OcrOutcome.RETRY);
                }
                parsed = ocrService.recognize(bytes);
            }
            catch (OcrApiException e) {
                String mark = e.errorMark();
                log.warn("任务 {} 图片资产 {} OCR 失败：code={}，可重试={}", task.getId(), asset.getId(),
                    e.getCode() == null ? mark : e.getCode(), e.isRetryable());
                if (e.isRetryable()) {
                    ocrWriter.finish(task, asset.getId(), token, TaskAsset.OCR_FAILED, null, mark);
                    return OcrScan.of(OcrOutcome.RETRY);
                }
                // 云端明确拒绝：当次即终态，不再等次数用尽（同一张图重试只会重复付费）
                ocrWriter.abandon(task, asset.getId(), token, maxAttempts, mark);
                return new OcrScan(OcrOutcome.REJECTED, mark);
            }
            catch (BizException e) {
                if (e.getResultCode() == ResultCode.CONFLICT) {
                    throw e;
                }
                String mark = String.valueOf(e.getResultCode().getSymbol());
                ocrWriter.finish(task, asset.getId(), token, TaskAsset.OCR_FAILED, null, mark);
                if (e.getResultCode() == ResultCode.NOT_CONFIGURED) {
                    return OcrScan.of(OcrOutcome.STOP);
                }
                if (isPermanentOcrInputError(e.getResultCode())) {
                    // 本地预检拒绝（尺寸/格式/归属）：同一张图重试无用，当次即终态
                    log.warn("任务 {} 图片资产 {} 预检拒绝：{}", task.getId(), asset.getId(), e.getMessage());
                    ocrWriter.abandon(task, asset.getId(), token, maxAttempts, mark);
                    return new OcrScan(OcrOutcome.REJECTED, mark);
                }
                return OcrScan.of(OcrOutcome.RETRY);
            }
            catch (Exception e) {
                ocrWriter.finish(task, asset.getId(), token, TaskAsset.OCR_FAILED, null,
                    TaskAsset.OCR_ERROR_UNCERTAIN);
                return OcrScan.of(OcrOutcome.RETRY);
            }
            // 提交阶段不在调用云端的 catch 内；落库/结算失败时须交事务回滚和告警，不可再标为 OCR 失败。
            // 落库的是**归一化后的行文本**（'\n' 分隔），不是官方 Content：
            // TaskTextWriter 按同一口径切行即可重建句子，不必（也不能）把 ocr_text 当 JSON 二次解析。
            log.info("任务 {} 图片资产 {} OCR 完成：{} 行文本，耗时 {} ms", task.getId(), asset.getId(),
                parsed.lines().size(), (System.nanoTime() - startedAt) / 1_000_000);
            ocrWriter.finish(task, asset.getId(), token,
                parsed.lines().isEmpty() ? TaskAsset.OCR_EMPTY : TaskAsset.OCR_DONE,
                parsed.lines().isEmpty() ? null : String.join("\n", parsed.lines()), null);
        }
        // 部分成功也先展示；失败任务不得发布不完整的“没有问题”报告。
        textWriter.refreshImages(task);
        return OcrScan.of(allDone ? OcrOutcome.DONE : OcrOutcome.RETRY);
    }

    /** 本地预检类拒绝（尺寸/格式/权限）：同一张图重试无用，直接判终态 */
    private static boolean isPermanentOcrInputError(ResultCode code) {
        return code == ResultCode.UNPROCESSABLE || code == ResultCode.FORBIDDEN
            || code == ResultCode.BAD_REQUEST;
    }

    /** 纯图片任务的收尾：OCR 文本就绪后直接分析并发布。 */
    private boolean analyzeImageTask(RecognitionTask task) {
        textWriter.refreshImages(task);
        if (sentenceMapper.selectMaxSequence(task.getId()) == 0) {
            return backoff(task, TaskStatus.INVALID, ERR_NO_TEXT, "图片里没有识别到可用文字");
        }
        return publishWithoutRoles(task);
    }

    /** SpeakerId 仅标识说话人，不推断催收方/客户；角色限定规则由引擎明确跳过。 */
    private boolean publishWithoutRoles(RecognitionTask task) {
        try {
            // 发布事务内完成 PARSING/历史 WAITING_ROLE → ANALYZING → COMPLETED。
            reportService.analyzeWithoutRoles(task);
            return true;
        }
        catch (Exception e) {
            log.warn("任务 {} 自动生成记录失败，异常类型：{}", task.getId(), e.getClass().getSimpleName());
            TaskStatus retryState = TaskStatus.WAITING_ROLE.name().equals(task.getStatus())
                ? TaskStatus.WAITING_ROLE : TaskStatus.PARSING;
            return backoff(task, retryState, ERR_NO_REPORT, "生成记录失败，稍后重试");
        }
    }


    /** QUEUED → 提交云端；成功即登记远端任务号并转 TRANSCRIBING */
    private boolean submitToCloud(RecognitionTask task) {
        String remoteTaskId;
        try {
            // 带上业务键（幂等键）：提交结果不确定时可按它排查与对账
            remoteTaskId = tingwuService.submitTranscription(task.getOssObject(),
                task.getIdempotencyKey());
        }
        catch (CloudSubmitRejectedException e) {
            // 先持久标记「确定拒绝」，再在独立事务内将 FAILED 与退额一起提交。
            // 若结算失败，仍保留此标记，由 SUBMITTING 的下一轮安全重试，不重复付费提交。
            if (!backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_REJECTED,
                "远端明确拒绝，正在退还预占额度")) {
                return false;
            }
            return settleRejected(task);
        }
        catch (BizException e) {
            // 其他业务失败不自动退款：受理边界未证实，保持额度等待人工核对。
            backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_FAILED, e.getMessage());
            return true;
        }
        catch (IllegalStateException e) {
            // 超时/网络等不确定失败：不盲目重提，进入对账
            backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_UNCERTAIN,
                "提交结果不确定，等待对账");
            return true;
        }
        try {
            return settlement.confirmAccepted(task, remoteTaskId, props.getPipeline().getPollIntervalSeconds());
        }
        catch (Exception e) {
            // 云端已经给出 TaskId：事务失败时尽力先持久化 ID，下一轮补结 RESERVED。
            // 持久化也失败则保留不确定态，绝不能盲目再次提交云端。
            log.warn("任务 {} 云端受理后的本地额度结算失败，尝试保全远端任务号", task.getId());
            if (taskMapper.markSubmitted(task.getId(), task.getVersion(), remoteTaskId,
                BusinessTime.now().plusSeconds(props.getPipeline().getPollIntervalSeconds())) == 1) {
                return true;
            }
            backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_UNCERTAIN, "远端已返回任务号，本地记录失败，等待人工核对");
            return true;
        }
    }

    private boolean settleRejected(RecognitionTask task) {
        try {
            return settlement.rejectAndRelease(task, ERR_SUBMIT_REJECTED,
                "远端明确拒绝任务，请检查配置后重新提交");
        }
        catch (Exception e) {
            log.warn("任务 {} 明确拒绝后的额度结算失败，保留拒绝标记等待重试", task.getId());
            return backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_REJECTED, "远端明确拒绝，退额待重试");
        }
    }

    /** SUBMITTING：按确定拒绝标记重试退额；不确定结果不盲重提云端。 */
    private boolean reconcileSubmit(RecognitionTask task) {
        if (ERR_SUBMIT_REJECTED.equals(task.getErrorCode()) && task.getTaskId() == null) {
            return settleRejected(task);
        }
        int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (task.getTaskId() != null) {
            try {
                settlement.ensureAcceptedConsumed(task);
            }
            catch (Exception e) {
                return backoff(task, TaskStatus.SUBMITTING, ERR_QUOTA_UNSETTLED, "远端已受理，额度结算待恢复");
            }
            return backoff(task, TaskStatus.TRANSCRIBING, null, null);
        }
        if (attempts >= props.getPipeline().getMaxAttempts()) {
            // 不盲重提，也不退还额度：留给人工对账，避免重复付费
            return backoff(task, TaskStatus.FAILED, ERR_SUBMIT_UNRECONCILED,
                "提交结果无法确认，已停止重试，等待人工对账");
        }
        return backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_UNCERTAIN, "等待对账重试");
    }

    /** TRANSCRIBING：主动查询远端状态（回调只负责唤醒） */
    private boolean pollRemote(RecognitionTask task) {
        if (task.getTaskId() == null) {
            return backoff(task, TaskStatus.SUBMITTING, ERR_SUBMIT_UNCERTAIN, "缺少远端任务号");
        }
        try {
            settlement.ensureAcceptedConsumed(task);
        }
        catch (Exception e) {
            return backoff(task, TaskStatus.TRANSCRIBING, ERR_QUOTA_UNSETTLED, "远端已受理，额度结算待恢复");
        }
        TingwuService.TaskInfo info = tingwuService.getTaskInfo(task.getTaskId());
        return switch (info.status()) {
            case COMPLETED -> parseAndPersist(task);
            case RUNNING, QUEUED -> backoff(task, TaskStatus.TRANSCRIBING, null, null);
            case FAILED -> {
                // 云端已受理但处理失败：不退额度（不是“未受理”）
                yield backoff(task, TaskStatus.FAILED, ERR_REMOTE_FAILED,
                    info.message() == null ? "远端任务失败" : info.message());
            }
            case UNKNOWN -> backoff(task, TaskStatus.TRANSCRIBING, ERR_REMOTE_UNKNOWN,
                "远端返回未知状态，稍后重试");
        };
    }

    /** 下载并解析结果，按时间序落库；空音频判 INVALID，其余等角色确认 */
    private boolean parseAndPersist(RecognitionTask task) {
        TingwuService.TaskInfo info = new TingwuService.TaskInfo(task.getTaskId(),
            TingwuService.RemoteStatus.COMPLETED, resolveTranscriptionUrl(task), null);
        TranscriptionParser.ParsedTranscription parsed;
        try {
            parsed = tingwuService.fetchTranscription(info);
        }
        catch (BizException e) {
            // 结果地址失效、体积/来源不合规、结构异常：停在 PARSING 并记录，等下一次重试
            return backoff(task, TaskStatus.PARSING, ERR_PARSE_FAILED, e.getMessage());
        }
        if (parsed.isEmpty() && !MediaKind.hasImage(task.getMediaKind())) {
            return backoff(task, TaskStatus.INVALID, ERR_NO_TEXT, "音频中没有可用语音");
        }
        textWriter.saveAudio(task, parsed);
        backfillDuration(task, parsed.durationMs());
        // 混合记录：音频句子落库后再把图片 OCR 的结果并进来，然后才等角色确认
        if (MediaKind.hasImage(task.getMediaKind())) {
            return finishOcr(task, TaskStatus.PARSING, !parsed.isEmpty());
        }
        return publishWithoutRoles(task);
    }

    /**
     * 真实时长回填（S05 承诺）：云端结果里的时长才是事实，客户端声明值只用于提交前预检。
     * 超过策略上限时不回头作废已付费的转写结果，只在日志里告警（口径见 S05 记录）。
     */
    private void backfillDuration(RecognitionTask task, long durationMs) {
        if (durationMs <= 0) {
            return;
        }
        int seconds = (int) Math.round(durationMs / 1000.0);
        // 数据库回填已由 textWriter 在墓碑/版本门禁事务中完成。
        task.setDurationSec(seconds);
        int maxSeconds = configService.load().maxRecordingMinutes() * 60;
        if (seconds > maxSeconds) {
            log.warn("任务 {} 真实时长 {} 秒超过策略上限 {} 秒（转写已完成，不作废结果）",
                task.getId(), seconds, maxSeconds);
        }
    }

    /** 结果地址来自远端查询（回调不参与）；这里重新查询一次，避免把地址落在库里 */
    private String resolveTranscriptionUrl(RecognitionTask task) {
        return tingwuService.getTaskInfo(task.getTaskId()).transcriptionUrl();
    }

    /**
     * PARSING / 历史 WAITING_ROLE → ANALYZING：发布报告前先进入分析态。
     * CAS 失败返回 false（说明任务已被删除或被别处推进），调用方据此放弃完成动作。
     */
    public boolean markAnalyzing(RecognitionTask task) {
        TaskStatus current = TaskStatus.fromLegacy(task.getStatus());
        if (current == null || !current.canTransitionTo(TaskStatus.ANALYZING)) {
            return false;
        }
        int updated = taskMapper.advance(task.getId(), task.getVersion(), TaskStatus.ANALYZING.name(),
            BusinessTime.now().plusSeconds(props.getPipeline().getBackoffSeconds()), null, null, null);
        if (updated == 0) {
            return false;
        }
        task.setStatus(TaskStatus.ANALYZING.name());
        task.setVersion(task.getVersion() + 1);
        return true;
    }

    /**
     * 标记完成前置条件：必须已有已发布报告版本。
     * 报告发布属 S08，本阶段没有发布者时一律拒绝，杜绝“先 COMPLETED 后无结果”。
     */
    public void markCompleted(RecognitionTask task, boolean hasPublishedReport) {
        if (!hasPublishedReport) {
            throw new BizException(ResultCode.CONFLICT, "报告尚未发布，不能把任务标记为完成");
        }
        TaskStatus current = TaskStatus.fromLegacy(task.getStatus());
        if (current == null || !current.canTransitionTo(TaskStatus.COMPLETED)) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不允许置为完成：" + task.getStatus());
        }
        if (!backoff(task, TaskStatus.COMPLETED, null, null)) {
            throw new BizException(ResultCode.CONFLICT, "任务状态已变化，报告发布已取消");
        }
    }

    /** 写入状态 + 退避 + 错误码，并释放租约 */
    private boolean backoff(RecognitionTask task, TaskStatus next, String errorCode, String errorMsg) {
        LocalDateTime nextAttempt = next == TaskStatus.TRANSCRIBING
            ? BusinessTime.now().plusSeconds(props.getPipeline().getPollIntervalSeconds())
            : BusinessTime.now().plusSeconds(props.getPipeline().getBackoffSeconds());
        LocalDateTime finishedAt = next.isTerminal() ? BusinessTime.now() : null;
        int updated = taskMapper.advance(task.getId(), task.getVersion(), next.name(),
            next.isTerminal() ? null : nextAttempt, errorCode, errorMsg, finishedAt);
        if (updated > 0) {
            task.setVersion(task.getVersion() + 1);
            task.setStatus(next.name());
            return true;
        }
        return false;
    }
}
