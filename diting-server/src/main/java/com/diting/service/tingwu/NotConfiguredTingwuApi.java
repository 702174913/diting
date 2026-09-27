package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

import java.util.Map;

/** 听悟凭据未配置：调用即 503，不伪造任务号、不返回假转写结果 */
public class NotConfiguredTingwuApi implements TingwuApi {

    static BizException notConfigured() {
        return new BizException(ResultCode.NOT_CONFIGURED,
            "通义听悟未配置：请配置 diting.aliyun.access-key-id / access-key-secret / tingwu.app-key 后重试");
    }

    @Override
    public Map<String, ?> createTask(String requestBodyJson) {
        throw notConfigured();
    }

    @Override
    public Map<String, ?> getTask(String taskId) {
        throw notConfigured();
    }
}
