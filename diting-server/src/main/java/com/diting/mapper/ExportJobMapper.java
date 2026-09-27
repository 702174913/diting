package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.ExportJob;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ExportJobMapper extends BaseMapper<ExportJob> {

    @Select("SELECT * FROM export_jobs WHERE ((status IN ('QUEUED','RETRY_WAIT') "
        + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "OR (status='RUNNING' AND (lease_until IS NULL OR lease_until <= NOW(3)))) "
        + "ORDER BY id LIMIT #{limit}")
    List<ExportJob> selectDue(@Param("limit") int limit);

    @Update("UPDATE export_jobs SET status='RUNNING', attempt_count=attempt_count+1, "
        + "lease_token=#{token}, lease_until=#{until}, next_attempt_at=NULL "
        + "WHERE id=#{id} AND attempt_count < #{maxAttempts} AND "
        + "((status IN ('QUEUED','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "OR (status='RUNNING' AND (lease_until IS NULL OR lease_until <= NOW(3))))")
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("until") java.time.LocalDateTime until, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE export_jobs SET status='FAILED', error_code='EXPORT_ATTEMPTS_EXHAUSTED', "
        + "finished_at=NOW(3), lease_token=NULL, lease_until=NULL WHERE id=#{id} AND attempt_count >= #{maxAttempts} "
        + "AND ((status IN ('QUEUED','RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(3))) "
        + "OR (status='RUNNING' AND (lease_until IS NULL OR lease_until <= NOW(3))))")
    int exhaust(@Param("id") Long id, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE export_jobs SET status=#{status}, error_code=#{error}, "
        + "next_attempt_at=#{nextAttempt}, finished_at=#{finishedAt}, lease_token=NULL, lease_until=NULL "
        + "WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token}")
    int finishFailure(@Param("id") Long id, @Param("token") String token,
                      @Param("status") String status, @Param("error") String error,
                      @Param("nextAttempt") java.time.LocalDateTime nextAttempt,
                      @Param("finishedAt") java.time.LocalDateTime finishedAt);

    @Update("UPDATE export_jobs SET status='COMPLETED', artifact_key=#{key}, expires_at=#{expires}, "
        + "finished_at=NOW(3), lease_token=NULL, lease_until=NULL "
        + "WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until > NOW(3)")
    int completeClaim(@Param("id") Long id, @Param("token") String token,
                      @Param("key") String key, @Param("expires") java.time.LocalDateTime expires);

    /** 到期产物：超过有效期仍有产物 key 的作业（含音频 ZIP 必须在原件到期前清理） */
    @Select("SELECT * FROM export_jobs WHERE artifact_key IS NOT NULL AND expires_at <= NOW(3) "
        + "ORDER BY id LIMIT #{limit}")
    List<ExportJob> selectExpiredArtifacts(@Param("limit") int limit);

    /** 产物已销毁：清空 key，保留作业记录用于追溯 */
    @Update("UPDATE export_jobs SET artifact_key = NULL WHERE id = #{id}")
    int clearArtifactKey(@Param("id") Long id);
}
