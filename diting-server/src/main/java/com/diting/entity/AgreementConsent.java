package com.diting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户注册协议同意快照（审计用，S19）。
 * 记录“用户当时同意的是哪一版”，协议更新不影响历史快照。
 */
@Data
@TableName("agreement_consents")
public class AgreementConsent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String agreementKey;

    private Integer version;

    private LocalDateTime agreedAt;

    private String ip;
}
