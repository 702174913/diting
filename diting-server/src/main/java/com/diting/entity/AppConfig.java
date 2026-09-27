package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产品策略配置（开发文档 §6.4）。
 * 主键是配置键而不是自增 ID；三个配置作为同一版本快照保存，保存时用 version 做乐观锁。
 */
@Data
@TableName("app_config")
public class AppConfig {

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;

    /** 配置快照版本，三个配置同步递增 */
    private Long version;

    private String remark;

    private LocalDateTime updatedAt;
}
