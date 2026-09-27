package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.DeletionJob;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface DeletionJobMapper extends BaseMapper<DeletionJob> {

    /** 到期可处理的清理作业：排队中，或退避时间已到的重试等待 */
    @Select("SELECT * FROM deletion_jobs "
        + "WHERE (status = 'QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "OR (status = 'RETRY_WAIT' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "OR (status = 'RUNNING' AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "ORDER BY id LIMIT #{limit}")
    List<DeletionJob> selectDue(@Param("limit") int limit);

    /** 领取时换令牌；正在执行的作业仅在旧租约过期后可被重新领取。 */
    @Update("UPDATE deletion_jobs SET status='RUNNING', attempt_count=attempt_count+1, "
        + "lease_token=#{token}, next_attempt_at=#{leaseUntil}, error_code=NULL "
        + "WHERE id=#{id} AND ((status='QUEUED' AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(3))) "
        + "OR (status='RETRY_WAIT' AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(3))) "
        + "OR (status='RUNNING' AND (next_attempt_at IS NULL OR next_attempt_at<=NOW(3))))")
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 在清理数据的事务里锁住当前代；rearm/夺租须等本次事务结束，旧代不得执行清理。 */
    @Select("SELECT * FROM deletion_jobs WHERE id=#{id} AND status='RUNNING' "
        + "AND lease_token=#{token} AND next_attempt_at>NOW(3) FOR UPDATE")
    DeletionJob lockClaimed(@Param("id") Long id, @Param("token") String token);

    /** 签名仍可 PUT：同事务内挂起至失效后复扫，等待不耗尽失败重试次数。 */
    @Update("UPDATE deletion_jobs SET status='RETRY_WAIT', lease_token=NULL, "
        + "next_attempt_at=#{until}, error_code='SIGNED_URL_PENDING', "
        + "attempt_count=GREATEST(0, attempt_count-1) "
        + "WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} "
        + "AND next_attempt_at>NOW(3)")
    int deferClaim(@Param("id") Long id, @Param("token") String token,
                   @Param("until") LocalDateTime until);

    /** 只允许当前尚未到期的工作者将失败写成退避或终态；旧代不改新代状态。 */
    @Update("UPDATE deletion_jobs SET status=#{next}, lease_token=NULL, "
        + "next_attempt_at=#{nextAttemptAt}, error_code=#{errorCode} "
        + "WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} "
        + "AND next_attempt_at>NOW(3)")
    int failClaim(@Param("id") Long id, @Param("token") String token, @Param("next") String next,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("errorCode") String errorCode);

    /**
     * 重新排期（重复抹除 / 之前失败的作业被再次请求）：
     * 复位状态、租约、结束时间与重试计数，并把新对象并入待清理清单。
     * 必须在**完成前**用 markCompletedRunning 的 CAS 版本收尾，否则新并入的对象会被清空。
     */
    @Update("UPDATE deletion_jobs SET status = 'QUEUED', remaining_objects = #{remaining}, "
        + "next_attempt_at = CASE WHEN error_code='SIGNED_URL_PENDING' "
        + "AND next_attempt_at>NOW(3) THEN next_attempt_at ELSE NULL END, "
        + "lease_token = NULL, finished_at = NULL, error_code = NULL, attempt_count = 0 "
        + "WHERE id = #{id}")
    int rearm(@Param("id") Long id, @Param("remaining") String remaining);

    /**
     * 完成清理（仅当作业仍处于 RUNNING）：如果期间用户又发起了一次抹除，
     * 作业已被 rearm 成 QUEUED，这里必须放弃收尾，让下一轮把新对象真正清掉。
     */
    @Update("UPDATE deletion_jobs SET status='COMPLETED', remaining_objects=NULL, "
        + "lease_token=NULL, next_attempt_at=NULL, finished_at=NOW(3), error_code=NULL "
        + "WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} "
        + "AND next_attempt_at>NOW(3)")
    int markCompletedRunning(@Param("id") Long id, @Param("token") String token);
}
