package com.diting.service.storage;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

import java.time.Duration;

/**
 * 未配置对象存储时的实现：调用即 503 NOT_CONFIGURED。
 * 生产缺凭据要显式失败，绝不返回看起来成功的假 URL（开发文档 §11）。
 */
public class NotConfiguredObjectStorage implements ObjectStorage {

    private static BizException notConfigured() {
        return new BizException(ResultCode.NOT_CONFIGURED,
            "对象存储未配置：请配置 diting.aliyun.access-key-id / access-key-secret / oss.bucket 后重试");
    }

    @Override
    public PresignedRequest presignPut(String objectKey, Duration ttl, String contentType) {
        throw notConfigured();
    }

    @Override
    public PresignedRequest presignGet(String objectKey, Duration ttl) {
        throw notConfigured();
    }

    @Override
    public ObjectMeta head(String objectKey) {
        throw notConfigured();
    }

    @Override
    public byte[] readPrefix(String objectKey, int maxBytes) {
        throw notConfigured();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        throw notConfigured();
    }

    @Override
    public byte[] read(String objectKey) {
        throw notConfigured();
    }

    @Override
    public void delete(String objectKey) {
        throw notConfigured();
    }
}
