package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.QuotaLedger;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 额度流水（一个任务一行，uk_task 防重复绑定）。
 * 状态转换用 CAS：影响行数为 0 说明状态已被别的请求改过，调用方不得再改计数。
 */
public interface QuotaLedgerMapper extends BaseMapper<QuotaLedger> {

    @Update("UPDATE quota_ledger SET state = #{next}, updated_at = #{now} "
        + "WHERE task_id = #{taskId} AND state = #{expected}")
    int casState(@Param("taskId") Long taskId, @Param("expected") String expected,
                 @Param("next") String next, @Param("now") LocalDateTime now);
}
