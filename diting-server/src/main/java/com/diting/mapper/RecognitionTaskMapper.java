package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.RecognitionTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务表：作业领取与推进都用「条件更新 + 影响行数」做 CAS。
 * 不用「先查再改」，否则两个工作者会同时处理同一个任务（重复付费提交）。
 */
public interface RecognitionTaskMapper extends BaseMapper<RecognitionTask> {

    /** 发布/角色修改的事务门禁：与删除任务行的 UPDATE 使用同一行锁。必须在事务内调用。 */
    @Select("SELECT * FROM recognition_tasks WHERE id = #{id} FOR UPDATE")
    RecognitionTask lockById(@Param("id") Long id);

    /**
     * 取一批到期任务：未删除、需要作业推进、退避时间已到、租约已过期。
     * 客户端轮询不会走这里，轮询只读状态、不触发调度。
     */
    @Select("SELECT * FROM recognition_tasks "
        + "WHERE deleted_at IS NULL "
        + "AND status IN ('QUEUED', 'SUBMITTING', 'TRANSCRIBING', 'PARSING', 'WAITING_ROLE') "
        + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3)) "
        + "AND (lease_until IS NULL OR lease_until < NOW(3)) "
        + "ORDER BY id LIMIT #{limit}")
    List<RecognitionTask> selectDue(@Param("limit") int limit);

    /**
     * 领取任务：版本匹配且租约已过期才成功，同时把状态推进到目标状态并登记租约。
     * 影响 0 行表示已被别人领取或状态已变，调用方必须放弃本次处理。
     */
    @Update("UPDATE recognition_tasks "
        + "SET version = version + 1, status = #{next}, lease_until = #{leaseUntil}, "
        + "attempt_count = attempt_count + 1 "
        + "WHERE id = #{id} AND version = #{version} AND deleted_at IS NULL "
        + "AND (lease_until IS NULL OR lease_until < NOW(3))")
    int claim(@Param("id") Long id, @Param("version") long version, @Param("next") String next,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 释放租约（不改变状态），用于本轮处理结束后让别的实例可以接手 */
    @Update("UPDATE recognition_tasks SET lease_until = NULL "
        + "WHERE id = #{id} AND version = #{version}")
    int releaseLease(@Param("id") Long id, @Param("version") long version);

    /** 推进状态：写状态、退避时间与错误码，并释放租约（版本 CAS，失败即放弃） */
    @Update("UPDATE recognition_tasks SET version = version + 1, status = #{next}, "
        + "next_attempt_at = #{nextAttemptAt}, error_code = #{errorCode}, "
        + "error_msg = #{errorMsg}, lease_until = NULL, finished_at = #{finishedAt} "
        + "WHERE id = #{id} AND version = #{version} AND deleted_at IS NULL")
    int advance(@Param("id") Long id, @Param("version") long version, @Param("next") String next,
                @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                @Param("errorCode") String errorCode, @Param("errorMsg") String errorMsg,
                @Param("finishedAt") LocalDateTime finishedAt);

    @Update("UPDATE recognition_tasks SET version=version+1, parse_failures=parse_failures+1, "
        + "status=#{next}, error_code=#{errorCode}, error_msg=#{message}, next_attempt_at=#{retryAt}, "
        + "finished_at=#{finishedAt}, lease_until=NULL WHERE id=#{id} AND version=#{version} "
        + "AND deleted_at IS NULL AND status IN ('TRANSCRIBING','PARSING')")
    int failParsing(@Param("id") Long id, @Param("version") long version,
                    @Param("next") String next, @Param("errorCode") String errorCode,
                    @Param("message") String message, @Param("retryAt") LocalDateTime retryAt,
                    @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 到期原件：保留期已过、且**还有对象没清理**的任务（音频或图片；文本保留时长另议，不在此删除）。
     * 用「还有对象」而不是 audio_purged 标志：纯图片任务没有音频，标志永远为 0，
     * 只看标志会让它每次都被捞出来，只音频标志又会漏掉图片。
     */
    @Select("SELECT * FROM recognition_tasks t "
        + "WHERE t.expires_at IS NOT NULL AND t.expires_at <= NOW(3) "
        + "AND (t.oss_object IS NOT NULL OR EXISTS (SELECT 1 FROM task_assets a "
        + "WHERE a.task_id = t.id AND a.object_purged = 0)) "
        + "ORDER BY t.id LIMIT #{limit}")
    List<RecognitionTask> selectExpiredOriginals(@Param("limit") int limit);

    /** 标记原件已清理（幂等） */
    @Update("UPDATE recognition_tasks SET audio_purged = 1 WHERE id = #{id} AND audio_purged = 0")
    int markAudioPurged(@Param("id") Long id);

    /**
     * 回填真实时长（云端转写结果）：客户端声明值只在拿到真实值前做预检，
     * 不作为报告与列表的事实来源，因此真实值直接覆盖。
     */
    @Update("UPDATE recognition_tasks SET duration_sec = #{durationSec} WHERE id = #{id}")
    int updateDuration(@Param("id") Long id, @Param("durationSec") Integer durationSec);

    /** 删除作业清理完任务关联明细后推进最终墓碑，不改变未删除任务。 */
    @Update("UPDATE recognition_tasks SET status='DELETED' WHERE id=#{id} "
        + "AND deleted_at IS NOT NULL AND status='DELETING'")
    int markDeletedAfterCleanup(@Param("id") Long id);

    /** 清空对象引用（清理完成后不再保留可访问路径） */
    @Update("UPDATE recognition_tasks SET oss_object = NULL WHERE id = #{id}")
    int clearObjectKey(@Param("id") Long id);

    /**
     * 回调唤醒：清掉退避让作业尽快领取；**不抢仍在有效期内的租约**——
     * 否则正在提交（CreateTask 已发出、远端任务号尚未落库）的工作者会被抢走，
     * 云端已受理的账单与任务号随之丢失。已被删除或终态任务不受影响。
     */
    @Update("UPDATE recognition_tasks SET next_attempt_at = NULL, "
        + "lease_until = CASE WHEN lease_until IS NOT NULL AND lease_until > NOW(3) "
        + "THEN lease_until ELSE NULL END "
        + "WHERE id = #{id} AND deleted_at IS NULL "
        + "AND status IN ('QUEUED', 'SUBMITTING', 'TRANSCRIBING', 'PARSING')")
    int wakeUp(@Param("id") Long id);

    /** 记录远端任务号并进入转录中（同一次 CAS，避免出现“有 taskId 但状态没跟上”） */
    @Update("UPDATE recognition_tasks SET version = version + 1, task_id = #{remoteTaskId}, "
        + "status = 'TRANSCRIBING', next_attempt_at = #{nextAttemptAt}, error_code = NULL, "
        + "lease_until = NULL "
        + "WHERE id = #{id} AND version = #{version} AND deleted_at IS NULL "
        + "AND status = 'SUBMITTING' AND task_id IS NULL")
    int markSubmitted(@Param("id") Long id, @Param("version") long version,
                      @Param("remoteTaskId") String remoteTaskId,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
