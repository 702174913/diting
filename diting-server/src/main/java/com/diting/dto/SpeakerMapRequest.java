package com.diting.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 保存说话人角色映射（开发文档 §7.2）。
 * expectedRevision 是乐观锁：与当前映射版本不一致返回 409，避免互相覆盖。
 * mapping 是 speakerId → AGENT/CUSTOMER/UNKNOWN；未知角色允许保留为 UNKNOWN，不强行二分。
 */
public record SpeakerMapRequest(
    @NotNull(message = "expectedRevision 不能为空") Long expectedRevision,
    @NotNull(message = "mapping 不能为空") Map<Integer, String> mapping
) {
}
