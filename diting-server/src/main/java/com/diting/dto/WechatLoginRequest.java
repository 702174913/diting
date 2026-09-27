package com.diting.dto;

import jakarta.validation.constraints.NotBlank;

public record WechatLoginRequest(
    @NotBlank(message = "code 不能为空") String code,
    Boolean agreed,
    java.util.Map<String, Integer> seenVersions
) {
}
