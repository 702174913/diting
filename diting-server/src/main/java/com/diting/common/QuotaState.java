package com.diting.common;

/**
 * 额度流水状态（开发文档 §6.4）。
 * 转换一律用 CAS（条件更新），防止重复扣减与重复退还。
 */
public enum QuotaState {

    /** 已预占：任务已登记、云端尚未确认受理 */
    RESERVED,
    /** 已消费：云端确认受理 */
    CONSUMED,
    /** 已退还：确定未被云端受理 */
    RELEASED
}
