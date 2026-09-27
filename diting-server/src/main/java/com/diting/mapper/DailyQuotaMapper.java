package com.diting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.diting.entity.DailyQuota;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 每日额度计数。
 * 增减都用「条件更新 + 影响行数」做 CAS，不用「先查再改」，
 * 否则并发下会超卖（两个人同时看到还剩 1 份，都通过检查）。
 */
public interface DailyQuotaMapper extends BaseMapper<DailyQuota> {

    /** 幂等建行：同一人同一天只会有一行（uk_user_date） */
    @Insert("INSERT IGNORE INTO daily_quota (user_id, business_date, reserved, consumed, version) "
        + "VALUES (#{userId}, #{businessDate}, 0, 0, 0)")
    int ensureRow(@Param("userId") Long userId, @Param("businessDate") LocalDate businessDate);

    /** 预占一份：仅当「预占 + 已消费 < 上限」时才成功，返回 0 表示额度不足 */
    @Update("UPDATE daily_quota SET reserved = reserved + 1, version = version + 1 "
        + "WHERE user_id = #{userId} AND business_date = #{businessDate} "
        + "AND reserved + consumed < #{limit}")
    int tryReserve(@Param("userId") Long userId, @Param("businessDate") LocalDate businessDate,
                   @Param("limit") int limit);

    /** 预占转已消费：总量不变，仅改变构成 */
    @Update("UPDATE daily_quota SET reserved = reserved - 1, consumed = consumed + 1, version = version + 1 "
        + "WHERE user_id = #{userId} AND business_date = #{businessDate} AND reserved > 0")
    int consumeReserved(@Param("userId") Long userId, @Param("businessDate") LocalDate businessDate);

    /** 退还预占：只有仍处于预占中的份数才能退 */
    @Update("UPDATE daily_quota SET reserved = reserved - 1, version = version + 1 "
        + "WHERE user_id = #{userId} AND business_date = #{businessDate} AND reserved > 0")
    int releaseReserved(@Param("userId") Long userId, @Param("businessDate") LocalDate businessDate);
}
