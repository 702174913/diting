package com.diting.service;

import com.diting.service.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * 对象存储的业务门面。
 * 具体实现由 {@link com.diting.config.ObjectStorageConfig} 按配置选择（真实 OSS / 内存实现 / 未配置），
 * 上传签名走 UploadSessionService，这里只保留给听悟的下载签名与删除。
 */
@Service
public class OssService {

    private final ObjectStorage storage;

    public OssService(ObjectStorage storage) {
        this.storage = storage;
    }

    /** 给听悟的下载 URL：有效期 4 小时（任务可能排队后才会真正读取） */
    public URL presignDownloadForTingwu(String objectKey) {
        return presignDownload(objectKey, Duration.ofHours(4));
    }

    /**
     * 按调用方 TTL 签发下载地址（P0 修复）：后台原件回放只给短 TTL，
     * 不得复用听悟的 4 小时链接——否则接口宣称 15 分钟、实际 4 小时有效。
     */
    public URL presignDownload(String objectKey, Duration ttl) {
        ObjectStorage.PresignedRequest presigned =
            storage.presignGet(objectKey, ttl);
        try {
            return new URL(presigned.url());
        }
        catch (MalformedURLException e) {
            throw new IllegalStateException("对象存储返回了非法的下载地址", e);
        }
    }

    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        storage.delete(objectKey);
    }
}
