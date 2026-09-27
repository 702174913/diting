package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户协议正文（版本化，S19）。
 * 4 个固定 key：service_terms / privacy_guide / personal_info_auth / recording_auth。
 * admin 后台每次保存 version +1；用户注册时快照当时版本到 agreement_consents。
 */
@Data
@TableName("agreements")
public class Agreement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agreementKey;

    private String title;

    private String contentHtml;

    private Integer version;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
