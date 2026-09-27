package com.diting.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计聚合（开发文档 §10 指标口径）。
 *
 * 约定：时间区间一律**左闭右开** `[from, to)`；业务日界按 Asia/Shanghai（由调用方换算好传入）；
 * 所有按任务的指标都按 task 去重，角色重算不重复计数。
 *
 * 统计面收敛（本轮）：后台只保留总览看板，这里只保留 `section=overview` 用到的聚合；
 * 违规图谱、合规风控、运营时段/额度、增长分层、规则版本时效等分组已随页面下线。
 */
public interface StatsMapper {

    /** 提交任务量：按 task 去重，看 created_at */
    @Select("SELECT COUNT(*) FROM recognition_tasks "
        + "WHERE created_at >= #{from} AND created_at < #{to}")
    long submittedTasks(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 先在全历史里选每个 task 首版，再筛窗口；窗口内重算不冒充新增报告。 */
    @Select("SELECT COUNT(*) FROM (SELECT task_id, published_at, "
        + "ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn "
        + "FROM report_versions WHERE status='PUBLISHED') x "
        + "WHERE rn=1 AND published_at >= #{from} AND published_at < #{to}")
    long publishedReports(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 当前队列：时点库存，不混作期间处理量 */
    @Select("SELECT status, COUNT(*) AS cnt FROM recognition_tasks "
        + "WHERE deleted_at IS NULL AND status IN ('QUEUED','SUBMITTING','TRANSCRIBING','PARSING','WAITING_ROLE','ANALYZING') "
        + "GROUP BY status")
    List<Map<String, Object>> queueByStatus();

    /** 完成量：区间内结束且状态为完成的 task */
    @Select("SELECT COUNT(*) FROM recognition_tasks "
        + "WHERE status = 'COMPLETED' AND finished_at >= #{from} AND finished_at < #{to}")
    long completedTasks(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 失败与无效量（分开统计，避免把“没有语音”算成失败） */
    @Select("SELECT status, COUNT(*) AS cnt FROM recognition_tasks "
        + "WHERE status IN ('FAILED','INVALID') AND finished_at >= #{from} AND finished_at < #{to} "
        + "GROUP BY status")
    List<Map<String, Object>> failureByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 耗时：提交到完成的时间差（只统计两段时间齐全的样本） */
    @Select("SELECT COUNT(*) AS samples, AVG(TIMESTAMPDIFF(SECOND, created_at, finished_at)) AS avg_seconds "
        + "FROM recognition_tasks WHERE status = 'COMPLETED' AND finished_at IS NOT NULL "
        + "AND finished_at >= #{from} AND finished_at < #{to}")
    Map<String, Object> durationStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // -----------------------------------------------------------------
    // 序列与漏斗：来源仍是 recognition_tasks / report_versions / users / upload_sessions / export_jobs
    // -----------------------------------------------------------------

    /** 每日首版同样先从全历史选，避免跨窗口重算伪造当天新增。 */
    @Select("SELECT DATE(published_at) AS day, COUNT(*) AS cnt FROM ("
        + "SELECT task_id, published_at, "
        + "ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY published_at, id) AS rn "
        + "FROM report_versions WHERE status='PUBLISHED') x "
        + "WHERE rn=1 AND published_at >= #{from} AND published_at < #{to} "
        + "GROUP BY DATE(published_at) ORDER BY day")
    List<Map<String, Object>> publishedByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 注册按日序列（users.created_at 是唯一可信注册时间，不依赖前端埋点） */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt FROM users "
        + "WHERE created_at >= #{from} AND created_at < #{to} GROUP BY DATE(created_at) ORDER BY day")
    List<Map<String, Object>> registrationsByDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 发生过角色确认的任务数：复核覆盖率的分子 */
    @Select("SELECT COUNT(DISTINCT target_id) FROM audit_log WHERE created_at >= #{from} "
        + "AND created_at < #{to} AND action = 'SPEAKER_MAP_CONFIRM'")
    long confirmedTasks(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 区间内新增注册用户 */
    @Select("SELECT COUNT(*) FROM users WHERE created_at >= #{from} AND created_at < #{to}")
    long registeredUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 活跃用户：区间内提交过任务的去重用户 */
    @Select("SELECT COUNT(DISTINCT user_id) FROM recognition_tasks "
        + "WHERE created_at >= #{from} AND created_at < #{to} AND deleted_at IS NULL")
    long activeUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 已授权用户：区间内签过条款的去重用户（漏斗第二环） */
    @Select("SELECT COUNT(DISTINCT user_id) FROM upload_sessions "
        + "WHERE consented_at >= #{from} AND consented_at < #{to}")
    long consentedUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 上传用户：区间内建过上传会话的去重用户（漏斗第三环） */
    @Select("SELECT COUNT(DISTINCT user_id) FROM upload_sessions "
        + "WHERE created_at >= #{from} AND created_at < #{to}")
    long uploadingUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 已产出报告的用户数：漏斗最后一环的分子，按用户去重 */
    @Select("SELECT COUNT(DISTINCT t.user_id) FROM report_versions rv "
        + "JOIN recognition_tasks t ON t.id = rv.task_id "
        + "WHERE rv.status = 'PUBLISHED' AND rv.published_at >= #{from} AND rv.published_at < #{to}")
    long reportedUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 导出过产物的用户数（只算 C 端用户域） */
    @Select("SELECT COUNT(DISTINCT owner_id) FROM export_jobs WHERE owner_domain = 'USER' "
        + "AND created_at >= #{from} AND created_at < #{to}")
    long exportedUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
