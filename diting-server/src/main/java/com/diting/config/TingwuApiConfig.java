package com.diting.config;

import com.diting.service.tingwu.AliyunTingwuApi;
import com.diting.service.tingwu.NotConfiguredTingwuApi;
import com.diting.service.tingwu.StubTingwuApi;
import com.diting.service.tingwu.TingwuApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 听悟客户端选择：与对象存储同样的策略。
 * - diting.storage.type=memory（测试）或 diting.tingwu.type=stub → 桩实现（仅测试）
 * - 凭据齐 → 真实 SDK
 * - 否则 → NOT_CONFIGURED（503），不假装提交成功
 */
@Slf4j
@Configuration
public class TingwuApiConfig {

    @Bean
    public TingwuApi tingwuApi(DitingProperties props) {
        String type = props.getAliyun().getTingwu().getType();
        if ("stub".equalsIgnoreCase(type)
            || "memory".equalsIgnoreCase(props.getStorage().getType())) {
            log.warn("听悟客户端使用桩实现（diting.tingwu.type={}）：仅限测试环境", type);
            return new StubTingwuApi();
        }
        if (ObjectStorageConfig.credentialsLookReal(props)
            && isReal(props.getAliyun().getTingwu().getAppKey())) {
            try {
                return new AliyunTingwuApi(props);
            }
            catch (Exception e) {
                log.error("听悟客户端初始化失败：{}", e.getMessage());
                return new NotConfiguredTingwuApi();
            }
        }
        log.warn("未检测到可用的听悟凭据：转写接口会返回 503 NOT_CONFIGURED");
        return new NotConfiguredTingwuApi();
    }

    private static boolean isReal(String value) {
        return value != null && !value.isBlank()
            && !ObjectStorageConfig.PLACEHOLDER.equals(value.trim());
    }
}
