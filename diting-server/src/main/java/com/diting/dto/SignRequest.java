package com.diting.dto;

import jakarta.validation.constraints.NotBlank;

public record SignRequest(
    @NotBlank(message = "文件名不能为空") String filename
) {
}
