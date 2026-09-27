package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.TaskAsset;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskAssetMapper extends BaseMapper<TaskAsset> {

    /** 任务的资产：音频在前（sequence 0），图片按用户选择顺序 */
    @Select("SELECT * FROM task_assets WHERE task_id = #{taskId} ORDER BY sequence")
    List<TaskAsset> selectByTask(@Param("taskId") Long taskId);

    /** 待 OCR 的图片（含失败重试用例：调用方按次数上限决定是否再试） */
    @Select("SELECT * FROM task_assets WHERE task_id = #{taskId} AND kind = 'IMAGE' "
        + "AND object_purged = 0 AND ocr_status IN ('PENDING', 'RUNNING', 'FAILED') ORDER BY sequence")
    List<TaskAsset> selectImagesToOcr(@Param("taskId") Long taskId);

    /** 新领取/超时回收都按同一条件更新，外层必须持有任务行锁。 */
    @Update("UPDATE task_assets SET ocr_status='RUNNING', ocr_attempts=ocr_attempts+1, "
        + "ocr_lease_token=#{token}, ocr_lease_until=#{until} WHERE id=#{id} AND task_id=#{taskId} AND object_purged=0 "
        + "AND ocr_attempts < #{maxAttempts} AND (ocr_status IN ('PENDING','FAILED') "
        + "OR (ocr_status='RUNNING' AND (ocr_lease_until IS NULL OR ocr_lease_until <= NOW(3))))")
    int claimOcr(@Param("id") Long id, @Param("taskId") Long taskId, @Param("token") String token,
                 @Param("until") java.time.LocalDateTime until, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE task_assets SET ocr_status=#{status}, ocr_text=#{text}, ocr_error=#{error}, "
        + "ocr_done_at=NOW(3), ocr_lease_token=NULL, ocr_lease_until=NULL "
        + "WHERE id=#{id} AND task_id=#{taskId} AND object_purged=0 AND ocr_status='RUNNING' "
        + "AND ocr_lease_token=#{token} AND ocr_lease_until > NOW(3)")
    int finishOcr(@Param("id") Long id, @Param("taskId") Long taskId, @Param("token") String token,
                  @Param("status") String status, @Param("text") String text, @Param("error") String error);

    @Update("UPDATE task_assets SET ocr_status='EXHAUSTED', ocr_lease_token=NULL, ocr_lease_until=NULL "
        + "WHERE id=#{id} AND task_id=#{taskId} AND ocr_attempts >= #{maxAttempts} "
        + "AND (ocr_status IN ('PENDING','FAILED') OR (ocr_status='RUNNING' "
        + "AND (ocr_lease_until IS NULL OR ocr_lease_until <= NOW(3))))")
    int exhaustOcr(@Param("id") Long id, @Param("taskId") Long taskId, @Param("maxAttempts") int maxAttempts);

    /**
     * 云端明确拒绝（开通/权限/图片本身的问题）：**不再等重试次数用尽**，当次即置终态并留下原因。
     * 仍按租约令牌 CAS，迟到的旧工作者不能改写结论。
     */
    @Update("UPDATE task_assets SET ocr_status='EXHAUSTED', ocr_error=#{error}, ocr_attempts=#{maxAttempts}, "
        + "ocr_lease_token=NULL, ocr_lease_until=NULL, ocr_done_at=NOW(3) "
        + "WHERE id=#{id} AND task_id=#{taskId} AND object_purged=0 AND ocr_status='RUNNING' "
        + "AND ocr_lease_token=#{token} AND ocr_lease_until > NOW(3)")
    int abandonOcr(@Param("id") Long id, @Param("taskId") Long taskId, @Param("token") String token,
                   @Param("maxAttempts") int maxAttempts, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM task_assets WHERE task_id=#{taskId} AND kind='IMAGE' "
        + "AND ocr_status IN ('PENDING','FAILED','RUNNING')")
    int countUnfinishedOcr(@Param("taskId") Long taskId);

    @Select("SELECT COUNT(*) FROM task_assets WHERE task_id=#{taskId} AND kind='IMAGE' AND ocr_status='EXHAUSTED'")
    int countExhaustedOcr(@Param("taskId") Long taskId);

    /** 清理：删除任务的全部资产行（对象删除由清理作业负责） */
    @Select("SELECT COUNT(*) FROM task_assets WHERE task_id = #{taskId}")
    int countByTask(@Param("taskId") Long taskId);

    /** 尚未清理对象引用的资产：到期清理与删除传播都按它推进 */
    @Select("SELECT * FROM task_assets WHERE task_id = #{taskId} AND object_purged = 0 ORDER BY sequence")
    List<TaskAsset> selectPurgeable(@Param("taskId") Long taskId);

    /** 标记对象已清理并清空引用（幂等；对象已删则不再保留可访问路径） */
    @Update("UPDATE task_assets SET object_purged = 1, object_key = '' "
        + "WHERE id = #{id} AND object_purged = 0")
    int markObjectPurged(@Param("id") Long id);
}
