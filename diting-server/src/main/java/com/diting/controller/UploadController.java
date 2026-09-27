package com.diting.controller;

import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.dto.UploadCompleteResponse;
import com.diting.dto.UploadSignRequest;
import com.diting.dto.UploadSignResponse;
import com.diting.service.UploadSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上传会话接口（开发文档 §5.1、§7.2）。
 *
 * 协议是「服务端签发预签名 + 客户端原始字节 PUT」：
 * 这里只签发与校验，不接受 multipart 表单上传，也不做后端中转。
 */
@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final UploadSessionService uploadSessionService;

    public UploadController(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    /** 签发上传会话与预签名 PUT */
    @PostMapping("/sign")
    public R<UploadSignResponse> sign(@Valid @RequestBody UploadSignRequest req) {
        Long userId = LoginUserHolder.getUserId();
        return R.ok(UploadSignResponse.of(
            uploadSessionService.sign(userId, req.filename(), req.consentVersion(), req.declaredSize())));
    }

    /** 重新签发同一会话的签名（原签名过期时用，objectKey 不变） */
    @PostMapping("/refresh")
    public R<UploadSignResponse> refresh(@Valid @RequestBody UploadCompleteResponse.Request req) {
        Long userId = LoginUserHolder.getUserId();
        return R.ok(UploadSignResponse.of(uploadSessionService.refresh(userId, req.uploadId())));
    }

    /** 上传完成校验：对象存在、大小、真实格式（不看扩展名） */
    @PostMapping("/complete")
    public R<UploadCompleteResponse> complete(@Valid @RequestBody UploadCompleteResponse.Request req) {
        Long userId = LoginUserHolder.getUserId();
        return R.ok(UploadCompleteResponse.of(uploadSessionService.complete(userId, req.uploadId())));
    }
}
