package com.diting.service.tingwu;

import java.util.Map;

/**
 * 测试用桩客户端：返回可编排的响应体，**仅测试环境启用**。
 * 让「响应结构 → 内部状态/句子」这条链路在没有云凭据时也能被完整测试。
 */
public class StubTingwuApi implements TingwuApi {

    /** 编排入口（测试线程）与作业线程不是同一个线程，因此用可见字段而不是 ThreadLocal */
    private volatile Map<String, ?> nextCreate;
    private volatile Map<String, ?> nextGet;

    public void willReturnCreate(Map<String, ?> response) {
        this.nextCreate = response;
    }

    public void willReturnGet(Map<String, ?> response) {
        this.nextGet = response;
    }

    @Override
    public Map<String, ?> createTask(String requestBodyJson) {
        Map<String, ?> response = nextCreate;
        if (response == null) {
            throw new IllegalStateException("StubTingwuApi 未编排 CreateTask 响应");
        }
        return response;
    }

    @Override
    public Map<String, ?> getTask(String taskId) {
        Map<String, ?> response = nextGet;
        if (response == null) {
            throw new IllegalStateException("StubTingwuApi 未编排 GetTaskInfo 响应");
        }
        return response;
    }
}
