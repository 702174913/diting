package com.diting.config;

import com.diting.service.storage.AliyunOssObjectStorage;
import com.diting.service.storage.InMemoryObjectStorage;
import com.diting.service.storage.NotConfiguredObjectStorage;
import com.diting.service.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储选择：
 * - diting.storage.type=memory → 内存实现（**仅测试**，让上传协议可离线验收）
 * - diting.storage.type=oss    → 强制使用阿里云 OSS
 * - 默认 auto：凭据像真实值时用 OSS，否则给 NOT_CONFIGURED（不伪造成功）
 */
@Slf4j
@Configuration
public class ObjectStorageConfig {

    /** 配置文件里的占位值，出现即视为“未配置” */
    static final String PLACEHOLDER = "请替换";

    @Bean
    public ObjectStorage objectStorage(DitingProperties props) {
        String type = props.getStorage() == null ? "auto" : props.getStorage().getType();
        if ("memory".equalsIgnoreCase(type)) {
            log.warn("对象存储使用内存实现（diting.storage.type=memory）：仅限测试环境，重启即丢数据");
            return new InMemoryObjectStorage("http://memory-storage.test");
        }
        if ("oss".equalsIgnoreCase(type)) {
            return new AliyunOssObjectStorage(props);
        }
        if (credentialsLookReal(props)) {
            return new AliyunOssObjectStorage(props);
        }
        log.warn("未检测到可用的对象存储凭据：上传接口会返回 503 NOT_CONFIGURED");
        return new NotConfiguredObjectStorage();
    }

    static boolean credentialsLookReal(DitingProperties props) {
        DitingProperties.Aliyun aliyun = props.getAliyun();
        if (aliyun == null) {
            return false;
        }
        return isReal(aliyun.getAccessKeyId()) && isReal(aliyun.getAccessKeySecret())
            && isReal(aliyun.getOss().getBucket()) && isReal(aliyun.getOss().getEndpoint());
    }

    private static boolean isReal(String value) {
        return value != null && !value.isBlank() && !PLACEHOLDER.equals(value.trim());
    }
}
