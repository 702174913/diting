package com.diting.config;

import com.diting.service.ocr.AliyunOcrApi;
import com.diting.service.ocr.NotConfiguredOcrApi;
import com.diting.service.ocr.OcrApi;
import com.diting.service.ocr.StubOcrApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OCR 客户端选择：与听悟、对象存储同一套策略。
 * - diting.aliyun.ocr.type=stub（或 storage=memory，测试）→ 桩实现（仅测试）
 * - AccessKey 齐 → 真实 SDK
 * - 否则 → NOT_CONFIGURED（503），不假装识别成功
 *
 * 注：OCR 复用与听悟/OSS 相同的 AccessKey；但它是**独立产品**，
 * 需要单独开通「通用文字识别」并授予 RAM 策略 AliyunOCRFullAccess。
 */
@Slf4j
@Configuration
public class OcrApiConfig {

    @Bean
    public OcrApi ocrApi(DitingProperties props) {
        String type = props.getAliyun().getOcr() == null ? "auto" : props.getAliyun().getOcr().getType();
        if ("stub".equalsIgnoreCase(type)
            || "memory".equalsIgnoreCase(props.getStorage().getType())) {
            log.warn("OCR 客户端使用桩实现（diting.aliyun.ocr.type={}）：仅限测试环境", type);
            return new StubOcrApi();
        }
        if (ObjectStorageConfig.credentialsLookReal(props)) {
            try {
                return new AliyunOcrApi(props);
            }
            catch (Exception e) {
                log.error("OCR 客户端初始化失败：{}", e.getMessage());
                return new NotConfiguredOcrApi();
            }
        }
        log.warn("未检测到可用的阿里云凭据：图片识别会返回 503 NOT_CONFIGURED");
        return new NotConfiguredOcrApi();
    }
}
