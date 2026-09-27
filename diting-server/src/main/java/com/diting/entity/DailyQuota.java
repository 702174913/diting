package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 每日额度计数（开发文档 §6.4）。
 * uk(user_id, business_date) 保证一人一天一行；reserved/consumed 的增减必须与
 * 任务登记在同一事务内并用 version 做 CAS，避免超卖。
 */
@Data
@TableName("daily_quota")
public class DailyQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** Asia/Shanghai 业务日期 */
    private LocalDate businessDate;

    /** 已预占份数 */
    private Integer reserved;

    /** 已消费份数 */
    private Integer consumed;

    private Long version;
}
