package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.common.TaskStatus;
import com.diting.entity.DeletionJob;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.DeletionJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.UserMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.mapper.UploadSessionMapper;
import com.diting.entity.TaskAsset;
import com.diting.entity.UploadSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 删除与抹除（开发文档 §5.3、§7.2）。
 *
 * 本轮语义（S13 补齐外部清理）：
 * - 先写**墓碑**（deleted_at 非空 + 状态 DELETING）并**立即拒绝一切读取**，可见性撤销不等清理任务；
 * - 同时登记一条 deletion_jobs（QUEUED）；重复请求不新建重复作业，但会把作业**重新排期**
 *   （清空租约与结束时间、并入新对象），保证「再抹除一次」是真的再清一遍；
 * - 真正删除 OSS 对象与导出产物的清理作业属 S13，本轮不宣称「已彻底删除」。
 */
@Service
public class DeletionService {

    public static final String TARGET_TASK = "TASK";
    public static final String TARGET_ERASE = "ERASE";
    public static final String STATUS_QUEUED = "QUEUED";

    private final RecognitionTaskMapper taskMapper;
    private final DeletionJobMapper deletionJobMapper;
    private final UserMapper userMapper;
    private final TaskAssetMapper taskAssetMapper;
    private final UploadSessionMapper uploadSessionMapper;

    public DeletionService(RecognitionTaskMapper taskMapper, DeletionJobMapper deletionJobMapper,
                           UserMapper userMapper, TaskAssetMapper taskAssetMapper,
                           UploadSessionMapper uploadSessionMapper) {
        this.taskMapper = taskMapper;
        this.deletionJobMapper = deletionJobMapper;
        this.userMapper = userMapper;
        this.taskAssetMapper = taskAssetMapper;
        this.uploadSessionMapper = uploadSessionMapper;
    }

    /** 单条删除：写墓碑 + 登记清理作业（幂等） */
    @Transactional
    public DeletionJob deleteTask(Long userId, Long taskId) {
        var user = userMapper.lockById(userId);
        if (user == null || user.getErasedAt() != null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销");
        }
        RecognitionTask task = taskMapper.selectById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            // 他人的记录一律 404；已删除的记录同样按不存在处理，避免用删除接口探测
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        if (task.getDeletedAt() == null) {
            markDeleted(task);
        }
        return enqueue(userId, TARGET_TASK, taskId, taskObjectKeys(task));
    }

    /** 全部抹除：把本人所有未删除记录一起写墓碑，再登记一个 ERASE 作业 */
    @Transactional
    public DeletionJob eraseAll(Long userId) {
        var user = userMapper.lockById(userId);
        if (user == null || user.getErasedAt() != null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销");
        }
        if (userMapper.revokeForErase(userId) != 1) {
            throw new BizException(ResultCode.CONFLICT, "抹除状态已变更");
        }
        List<RecognitionTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<RecognitionTask>()
            .eq(RecognitionTask::getUserId, userId)
            .isNull(RecognitionTask::getDeletedAt));
        List<String> objects = new ArrayList<>();
        for (RecognitionTask task : tasks) {
            markDeleted(task);
            objects.addAll(taskObjectKeys(task));
        }
        // 已签发但尚未登记任务的会话同样保留 key：清理后 PUT 可重建对象，需过期后复扫。
        for (UploadSession session : uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
            .eq(UploadSession::getUserId, userId))) {
            objects.add(nullToEmpty(session.getObjectKey()));
        }
        objects.removeIf(String::isBlank);
        return enqueue(userId, TARGET_ERASE, userId, objects);
    }

    /** 查询清理作业状态：只返回本人作业与最小状态元数据 */
    public DeletionJob requireOwnedJob(Long userId, Long jobId) {
        DeletionJob job = deletionJobMapper.selectById(jobId);
        if (job == null || !job.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "清理作业不存在");
        }
        return job;
    }

    private List<String> taskObjectKeys(RecognitionTask task) {
        List<String> keys = new ArrayList<>();
        keys.add(nullToEmpty(task.getOssObject()));
        for (TaskAsset asset : taskAssetMapper.selectByTask(task.getId())) {
            keys.add(nullToEmpty(asset.getObjectKey()));
        }
        keys.removeIf(String::isBlank);
        return keys;
    }

    private void markDeleted(RecognitionTask task) {
        task.setDeletedAt(BusinessTime.now());
        TaskStatus current = TaskStatus.fromLegacy(task.getStatus());
        task.setStatus(current != null && current.canTransitionTo(TaskStatus.DELETING)
            ? TaskStatus.DELETING.name()
            : TaskStatus.DELETED.name());
        task.setNextAttemptAt(null);
        task.setLeaseUntil(null);
        taskMapper.updateById(task);
    }

    /**
     * 幂等登记：同一目标只保留一个作业（uk_target）。
     * 已有作业时**必须重新排期**：抹除是「再请求一次就真的再清一遍」的动作——
     * 上一次作业已完成/失败，期间新上传的录音不能被旧的 COMPLETED 状态掩盖，
     * 否则接口回 COMPLETED，而原件与文本永远留着。
     */
    private DeletionJob enqueue(Long userId, String targetType, Long targetId, List<String> objects) {
        DeletionJob existing = findJob(targetType, targetId);
        if (existing != null) {
            String remaining = toJson(mergeObjects(existing.getRemainingObjects(), objects));
            deletionJobMapper.rearm(existing.getId(), remaining);
            existing.setStatus(STATUS_QUEUED);
            existing.setRemainingObjects(remaining);
            existing.setFinishedAt(null);
            existing.setErrorCode(null);
            existing.setAttemptCount(0);
            return existing;
        }
        LocalDateTime now = BusinessTime.now();
        DeletionJob job = new DeletionJob();
        job.setUserId(userId);
        job.setTargetType(targetType);
        job.setTargetId(targetId);
        job.setStatus(STATUS_QUEUED);
        job.setRemainingObjects(toJson(objects));
        job.setAttemptCount(0);
        job.setCreatedAt(now);
        try {
            deletionJobMapper.insert(job);
        }
        catch (DuplicateKeyException e) {
            // 并发重复请求：由唯一键兜底，返回已登记的那个作业
            DeletionJob winner = findJob(targetType, targetId);
            if (winner == null) {
                throw e;
            }
            String remaining = toJson(mergeObjects(winner.getRemainingObjects(), objects));
            deletionJobMapper.rearm(winner.getId(), remaining);
            winner.setStatus(STATUS_QUEUED);
            winner.setRemainingObjects(remaining);
            return winner;
        }
        return job;
    }

    private DeletionJob findJob(String targetType, Long targetId) {
        return deletionJobMapper.selectOne(new LambdaQueryWrapper<DeletionJob>()
            .eq(DeletionJob::getTargetType, targetType)
            .eq(DeletionJob::getTargetId, targetId)
            .last("FOR UPDATE"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 待清理对象清单（P2 修复：与 PrivacyController 共用同一口径）。
     * mergeObjects 已保证：历史遗留的 '[""]' 空串占位在合并时被过滤，
     * 新写入的 remaining 不会再含空串；但历史作业仍可能是旧格式，
     * 这里解析时同样过滤空串，避免“幽灵 pending”。
     */
    public static List<String> pendingObjects(String remainingJson) {
        List<String> out = new ArrayList<>();
        for (String item : parseJsonArray(remainingJson)) {
            if (item != null && !item.isBlank() && !out.contains(item)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 合并待清理对象：保留原有顺序并去重（同一对象不重复列） */
    private static List<String> mergeObjects(String existingJson, List<String> added) {
        List<String> merged = new ArrayList<>();
        for (String item : parseJsonArray(existingJson)) {
            if (!merged.contains(item)) {
                merged.add(item);
            }
        }
        for (String item : added) {
            if (item != null && !item.isBlank() && !merged.contains(item)) {
                merged.add(item);
            }
        }
        return merged;
    }

    /** 解析 remaining_objects：只存对象 key，写入时已去掉引号与逗号，可用最简解析 */
    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> values = new ArrayList<>();
        for (String part : body.split(",")) {
            String value = part.trim().replace("\"", "");
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String toJson(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(values.get(i).replace("\"", "")).append('"');
        }
        return builder.append(']').toString();
    }
}
