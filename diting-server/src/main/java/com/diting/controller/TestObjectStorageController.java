package com.diting.controller;

import com.diting.common.BizException;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.mapper.UploadSessionMapper;
import com.diting.entity.UploadSession;
import com.diting.service.storage.InMemoryObjectStorage;
import com.diting.service.storage.ObjectStorage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * **仅测试 profile** 的对象存储写入口。
 * 用于在没有云凭据的情况下走通「签名 → 送字节 → 校验 → 登记」全链路；
 * 生产不会加载（@Profile("test")），且必须配合 diting.storage.type=memory。
 */
@Profile("test")
@RestController
@RequestMapping("/api/test/object-storage")
public class TestObjectStorageController {

    private final ObjectStorage objectStorage;
    private final UploadSessionMapper uploadSessionMapper;

    public TestObjectStorageController(ObjectStorage objectStorage, UploadSessionMapper uploadSessionMapper) {
        this.objectStorage = objectStorage;
        this.uploadSessionMapper = uploadSessionMapper;
    }

    public record PutRequest(@NotBlank String uploadId, @NotBlank String contentBase64) {
    }

    /** 读取对象字节（base64）：仅供离线导出内容断言，生产不加载 */
    @GetMapping("/object")
    public R<Map<String, Object>> get(@RequestParam String key) {
        if (!(objectStorage instanceof InMemoryObjectStorage memory)) {
            throw new BizException(com.diting.common.ResultCode.NOT_CONFIGURED,
                "该接口只在 diting.storage.type=memory 的测试环境可用");
        }
        byte[] content = memory.read(key);
        if (content == null) {
            throw new BizException(com.diting.common.ResultCode.NOT_FOUND, "对象不存在");
        }
        return R.ok(Map.of("key", key, "size", content.length,
            "contentBase64", Base64.getEncoder().encodeToString(content)));
    }

    @PostMapping("/put")
    public R<Map<String, Object>> put(@Validated @RequestBody PutRequest req) {
        if (!(objectStorage instanceof InMemoryObjectStorage memory)) {
            throw new BizException(ResultCode.NOT_CONFIGURED,
                "该接口只在 diting.storage.type=memory 的测试环境可用");
        }
        long uploadId;
        try {
            uploadId = Long.parseLong(req.uploadId());
        }
        catch (NumberFormatException e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "uploadId 必须是数字");
        }
        UploadSession session = uploadSessionMapper.selectById(uploadId);
        if (session == null) {
            throw new BizException(ResultCode.NOT_FOUND, "上传记录不存在");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(req.contentBase64());
        }
        catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "内容不是合法的 base64");
        }
        memory.putObject(session.getObjectKey(), content, "application/octet-stream");
        return R.ok(Map.of("objectKey", session.getObjectKey(), "size", content.length));
    }
}
