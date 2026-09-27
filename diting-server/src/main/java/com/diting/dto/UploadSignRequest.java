package com.diting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 申请上传签名。
 * filename 只用于预检与展示（真实格式按文件头判定）；declaredSize 同理，仅用于提前拒绝超大文件；
 * consentVersion 是同意条款版本，缺它就签不出 URL（UPLOAD-08）。
 */
public record UploadSignRequest(
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名最长 255 位") String filename,

    @NotBlank(message = "请先同意录音处理条款")
    @Size(max = 32, message = "条款版本最长 32 位") String consentVersion,

    @PositiveOrZero(message = "文件大小不能为负") Long declaredSize
) {
}
