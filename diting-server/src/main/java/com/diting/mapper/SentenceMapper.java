package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.Sentence;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SentenceMapper extends BaseMapper<Sentence> {

    /** 按任务最新已发布报告计数；通话级违规单独计数，不能被零命中句掩盖。 */
    @Select("""
        <script>
        SELECT t.id AS task_id,
          (SELECT COUNT(*) FROM sentences s WHERE s.task_id = t.id) AS total,
          COUNT(DISTINCT v.sentence_id) AS flagged,
          COUNT(v.id) AS violations,
          rv.id AS report_version_id
        FROM recognition_tasks t
        LEFT JOIN report_versions rv ON rv.task_id = t.id AND rv.status = 'PUBLISHED'
          AND NOT EXISTS (SELECT 1 FROM report_versions newer
            WHERE newer.task_id = t.id AND newer.status = 'PUBLISHED' AND newer.version &gt; rv.version)
        LEFT JOIN violations v ON v.report_version_id = rv.id
        WHERE t.deleted_at IS NULL AND t.id IN
          <foreach collection="taskIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY t.id, rv.id
        </script>
        """)
    java.util.List<java.util.Map<String, Object>> selectTaskCounts(@Param("taskIds") java.util.List<Long> taskIds);

    /**
     * 任务内最大句序（无句子返回 0）。
     * 图片 OCR 的行要接在已有句子之后，所以需要它而不是把整表读进内存。
     */
    @Select("SELECT IFNULL(MAX(sequence), 0) FROM sentences WHERE task_id = #{taskId}")
    int selectMaxSequence(@Param("taskId") Long taskId);
}
