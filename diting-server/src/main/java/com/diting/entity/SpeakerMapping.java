package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 说话人角色映射（开发文档 §6.2）。
 * speakerId 是听悟声学聚类标签，与业务角色 AGENT/CUSTOMER/UNKNOWN 分离；
 * 每次角色变更写入新的 revision，历史版本不覆盖。
 */
@Data
@TableName("speaker_mappings")
public class SpeakerMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 声学聚类标签 */
    private Integer speakerId;

    /** AGENT / CUSTOMER / UNKNOWN */
    private String role;

    /** 映射版本，从 1 起递增 */
    private Long revision;

    /** 确认者 ID */
    private Long confirmedBy;

    /** USER / ADMIN */
    private String confirmedDomain;

    private LocalDateTime confirmedAt;
}
