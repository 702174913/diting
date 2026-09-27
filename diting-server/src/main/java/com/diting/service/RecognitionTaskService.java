package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.MediaKind;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.entity.TaskAsset;
import com.diting.entity.UploadSession;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 识别任务状态机：待提交 → 已提交(ONGOING) → 成功/失败/无效 */
@Service
public class RecognitionTaskService {

    private final RecognitionTaskMapper taskMapper;
    private final com.diting.mapper.UserMapper userMapper;
    private final OcrService ocrService;
    private final SentenceMapper sentenceMapper;
    private final TingwuService tingwuService;
    private final ConfigService configService;
    private final QuotaService quotaService;
    private final UploadSessionService uploadSessionService;
    private final com.diting.service.lifecycle.RetentionService retentionService;
    private final com.diting.mapper.TaskAssetMapper taskAssetMapper;
    private final com.diting.config.DitingProperties props;

    public RecognitionTaskService(RecognitionTaskMapper taskMapper, SentenceMapper sentenceMapper,
                                  TingwuService tingwuService, ConfigService configService,
                                  QuotaService quotaService, UploadSessionService uploadSessionService,
                                  com.diting.service.lifecycle.RetentionService retentionService,
                                  com.diting.mapper.TaskAssetMapper taskAssetMapper,
                                  com.diting.config.DitingProperties props,
                                  com.diting.mapper.UserMapper userMapper, OcrService ocrService) {
        this.userMapper = userMapper;
        this.ocrService = ocrService;
        this.taskMapper = taskMapper;
        this.sentenceMapper = sentenceMapper;
        this.tingwuService = tingwuService;
        this.configService = configService;
        this.quotaService = quotaService;
        this.uploadSessionService = uploadSessionService;
        this.retentionService = retentionService;
        this.taskAssetMapper = taskAssetMapper;
        this.props = props;
    }

    /**
     * 登记识别任务并预占额度（同一事务，任一步失败整体回滚）。
     *
     * 上传可信：只接受本人**已通过 complete 校验**的 uploadId，objectKey 由服务端从会话里取，
     * 客户端无法指定任意路径（UPLOAD-06）。
     *
     * 幂等：同一 (user_id, idempotency_key) 重复提交返回既有任务，不重复扣份数；
     * 客户端没给幂等键时按 uploadId 派生 —— 一次上传只会登记一个任务。
     *
     * 边界：云端 CreateTask 不在这里发起——那属于 S06 的持久作业管线。
     */
    @Transactional
    public RecognitionTask submit(Long userId, Long uploadId, Integer declaredDurationSec,
                                  String idempotencyKey) {
        return submit(userId, List.of(uploadId), declaredDurationSec, idempotencyKey);
    }

    /**
     * 登记任务（S16：一次提交可含音频 ≤1 + 图片 ≤6）。
     *
     * 校验顺序有意为之：先把全部会话取出来判类型与数量，再逐个消费——
     * 避免「消费了前几个、后面校验失败」把用户的会话白白烧掉。
     * 额度按**一次提交一份**计，与是否只传图片无关。
     */
    @Transactional
    public RecognitionTask submit(Long userId, List<Long> uploadIds, Integer declaredDurationSec,
                                  String idempotencyKey) {
        // 与 eraseAll 使用同一用户行锁；擦除先提交时本次登记直接拒绝，登记先提交时擦除会看到新任务。
        var user = userMapper.lockById(userId);
        if (user == null || user.getErasedAt() != null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销");
        }
        List<Long> ids = normalizeUploadIds(uploadIds);
        String key = normalizeIdempotencyKey(ids, idempotencyKey);
        RecognitionTask existing = findByIdempotencyKey(userId, key);
        if (existing != null) {
            return existing;
        }

        ConfigService.PolicyConfig config = configService.load();
        int maxSeconds = config.maxRecordingMinutes() * 60;
        if (declaredDurationSec != null && declaredDurationSec > maxSeconds) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "录音时长超过上限（" + config.maxRecordingMinutes() + " 分钟）");
        }

        // 只认本人的会话；这里只读校验，真正消费在后面统一做
        List<UploadSession> sessions = ids.stream()
            .map(id -> uploadSessionService.requireOwned(userId, id))
            .toList();
        List<UploadSession> audioSessions = sessions.stream()
            .filter(s -> !MediaKind.IMAGE.name().equals(s.getMediaKind()))
            .toList();
        List<UploadSession> imageSessions = sessions.stream()
            .filter(s -> MediaKind.IMAGE.name().equals(s.getMediaKind()))
            .toList();
        if (audioSessions.size() > 1) {
            throw new BizException(ResultCode.UNPROCESSABLE, "一次只能提交一段录音");
        }
        int maxImages = props.getMedia().getMaxImagesPerTask();
        if (imageSessions.size() > maxImages) {
            throw new BizException(ResultCode.UNPROCESSABLE, "一次最多上传 " + maxImages + " 张图片");
        }
        // 缺少实际受理能力时在消费上传会话和预占额度前拒绝；混合任务两端都须已配置。
        if (!audioSessions.isEmpty() && !tingwuService.isConfigured()) {
            throw new BizException(ResultCode.NOT_CONFIGURED, "音频转写服务未配置");
        }
        if (!imageSessions.isEmpty() && !ocrService.isConfigured()) {
            throw new BizException(ResultCode.NOT_CONFIGURED, "图片识别服务未配置");
        }

        List<UploadSession> consumed = sessions.stream()
            .map(s -> uploadSessionService.consumeForSubmit(userId, s.getId()))
            .toList();
        UploadSession audio = audioSessions.isEmpty() ? null : consumed.stream()
            .filter(s -> s.getId().equals(audioSessions.get(0).getId()))
            .findFirst()
            .orElse(null);

        RecognitionTask task = new RecognitionTask();
        task.setUserId(userId);
        // 纯图片任务没有音频：uploadId/ossObject 留空，资产表才是事实来源
        task.setUploadId(audio == null ? null : audio.getId());
        task.setOssObject(audio == null ? null : audio.getObjectKey());
        task.setMediaKind(MediaKind.derive(audio != null, !imageSessions.isEmpty()).name());
        // 客户端声明值；等云端返回真实时长后覆盖（S05），期间不作为报告事实
        task.setDurationSec(audio == null ? null : declaredDurationSec);
        // 上传已通过 complete 校验，登记后直接进入队列（UPLOADED 语义由 upload_sessions.VERIFIED 承载）
        task.setStatus(TaskStatus.QUEUED.name());
        task.setSpeakerMappingConfirmed(false);
        task.setIdempotencyKey(key);
        task.setCreatedAt(BusinessTime.now());
        // 保留期从**上传时间**起算（开发文档 §9）：音频与图片取各自上传时间中最早的一个
        LocalDateTime uploadedAt = consumed.stream()
            .map(UploadSession::getCreatedAt)
            .filter(java.util.Objects::nonNull)
            .min(LocalDateTime::compareTo)
            .orElseGet(BusinessTime::now);
        task.setExpiresAt(retentionService.expiresAt(uploadedAt));
        try {
            taskMapper.insert(task);
        }
        catch (DuplicateKeyException e) {
            // 并发重复提交：唯一键拦下第二条，返回已登记的那条任务（不重复扣份数）
            RecognitionTask winner = findByIdempotencyKey(userId, key);
            if (winner == null) {
                throw e;
            }
            return winner;
        }

        persistAssets(task, consumed, audio);
        quotaService.reserve(userId, task.getId(), config.dailyFreeQuota());
        return task;
    }

    /** 资产：音频固定 sequence 0，图片按用户提交顺序 1..N */
    private void persistAssets(RecognitionTask task, List<UploadSession> consumed, UploadSession audio) {
        LocalDateTime now = BusinessTime.now();
        int sequence = 0;
        if (audio != null) {
            taskAssetMapper.insert(assetOf(task.getId(), audio, sequence, TaskAsset.OCR_SKIPPED, now));
        }
        for (UploadSession session : consumed) {
            if (session.getId().equals(audio == null ? null : audio.getId())) {
                continue;
            }
            sequence++;
            taskAssetMapper.insert(assetOf(task.getId(), session, sequence, TaskAsset.OCR_PENDING, now));
        }
    }

    private static TaskAsset assetOf(Long taskId, UploadSession session, int sequence, String ocrStatus,
                                     LocalDateTime now) {
        TaskAsset asset = new TaskAsset();
        asset.setTaskId(taskId);
        asset.setUploadId(session.getId());
        asset.setKind(MediaKind.IMAGE.name().equals(session.getMediaKind())
            ? TaskAsset.KIND_IMAGE : TaskAsset.KIND_AUDIO);
        asset.setSequence(sequence);
        asset.setObjectKey(session.getObjectKey());
        asset.setFilename(session.getFilename());
        asset.setMediaFormat(session.getMediaFormat());
        asset.setSizeBytes(session.getSizeBytes());
        asset.setOcrStatus(ocrStatus);
        asset.setCreatedAt(now);
        return asset;
    }

    private static List<Long> normalizeUploadIds(List<Long> uploadIds) {
        if (uploadIds == null) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请先上传文件");
        }
        List<Long> ids = uploadIds.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请先上传文件");
        }
        return ids;
    }

    /**
     * 幂等键：客户端显式提供优先；否则由上传会话 id 派生。
     * 派生值形如 auto:<sha256 前 56 位>，总长不超过列宽 64；
     * 单文件提交与历史行为完全一致（用 uploadId 本身派生），多文件按顺序拼接后派生。
     */
    static String normalizeIdempotencyKey(List<Long> uploadIds, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String trimmed = idempotencyKey.trim();
            if (trimmed.length() > 64) {
                throw new BizException(ResultCode.UNPROCESSABLE, "idempotencyKey 最长 64 位");
            }
            return trimmed;
        }
        String seed = uploadIds.size() == 1
            ? String.valueOf(uploadIds.get(0))
            : uploadIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return "auto:" + sha256Hex(seed).substring(0, 56);
    }

    static String normalizeIdempotencyKey(Long uploadId, String idempotencyKey) {
        return normalizeIdempotencyKey(List.of(uploadId), idempotencyKey);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private RecognitionTask findByIdempotencyKey(Long userId, String key) {
        return taskMapper.selectOne(new LambdaQueryWrapper<RecognitionTask>()
            .eq(RecognitionTask::getUserId, userId)
            .eq(RecognitionTask::getIdempotencyKey, key));
    }

    public List<RecognitionTask> listOfUser(Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<RecognitionTask>()
            .eq(RecognitionTask::getUserId, userId)
            .isNull(RecognitionTask::getDeletedAt)
            .orderByDesc(RecognitionTask::getCreatedAt));
    }

    /** 分页列表：已删除的记录不出现在列表里；可按状态筛选 */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<RecognitionTask> pageOfUser(
            Long userId, com.diting.common.PageQuery query, String status) {
        return taskMapper.selectPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(query.page(), query.pageSize()),
            new LambdaQueryWrapper<RecognitionTask>()
                .eq(RecognitionTask::getUserId, userId)
                .isNull(RecognitionTask::getDeletedAt)
                .eq(status != null && !status.isBlank(), RecognitionTask::getStatus,
                    status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT))
                .orderByDesc(RecognitionTask::getCreatedAt));
    }

    /** 单条任务的句子计数，供列表文案与报告概览共用 */
    public record SentenceCounts(int total, int flagged, int violations, boolean reportPublished) {
    }

    /** 某条任务的逐句判读结果，按时间序返回（报告页直接顺序渲染） */
    public List<Sentence> sentencesOf(Long taskId) {
        return sentenceMapper.selectList(new LambdaQueryWrapper<Sentence>()
            .eq(Sentence::getTaskId, taskId)
            .orderByAsc(Sentence::getStartMs));
    }

    /**
     * 批量取句子计数，避免列表逐条查库。
     * flagged/violations 仅来自最新已发布报告；无报告不能被当作无违规报告。
     */
    public Map<Long, SentenceCounts> countSentences(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SentenceCounts> counts = new HashMap<>();
        for (Map<String, Object> row : sentenceMapper.selectTaskCounts(taskIds)) {
            Object taskId = row.get("task_id");
            if (!(taskId instanceof Number id)) {
                continue;
            }
            counts.put(id.longValue(), new SentenceCounts(asInt(row.get("total")), asInt(row.get("flagged")),
                asInt(row.get("violations")), row.get("report_version_id") != null));
        }
        return counts;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public RecognitionTask requireOwned(Long id, Long userId) {
        RecognitionTask task = taskMapper.selectById(id);
        // 墓碑优先：已删除的记录对读取一律表现为不存在（可见性撤销不等清理任务）
        if (task == null || task.getDeletedAt() != null || !task.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return task;
    }

    /** 后台视角的可见性检查：已删除记录对后台同样表现为不存在 */
    public RecognitionTask requireVisibleForAdmin(Long id) {
        RecognitionTask task = taskMapper.selectById(id);
        if (task == null || task.getDeletedAt() != null) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return task;
    }

    /**
     * 客户端查询任务状态：**只读**，不触发任何调度（开发文档 §5.3）。
     * 真正的推进在后台作业管线里，页面关掉也会继续进行。
     */
    public RecognitionTask refreshStatus(Long id, Long userId) {
        return requireOwned(id, userId);
    }

    /** 回调唤醒：把退避时间清掉，让作业下一轮优先领取（幂等，重复/乱序回调都安全） */
    public boolean wakeUp(Long taskId) {
        return taskMapper.wakeUp(taskId) > 0;
    }

    /** 一键彻底删除：OSS 文件 + 任务 + 句子（录音与结果联动清除，隐私承诺闭环） */
    public void erase(Long userId, OssService ossService) {
        List<RecognitionTask> tasks = listOfUser(userId);
        for (RecognitionTask task : tasks) {
            if (task.getOssObject() != null) {
                ossService.delete(task.getOssObject());
            }
            sentenceMapper.delete(new LambdaQueryWrapper<Sentence>()
                .eq(Sentence::getTaskId, task.getId()));
            taskMapper.deleteById(task.getId());
        }
    }
}
