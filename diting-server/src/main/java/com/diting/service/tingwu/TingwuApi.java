package com.diting.service.tingwu;

import java.util.Map;

/**
 * 通义听悟 OpenAPI 端口。
 *
 * 为什么要这一层：真实调用需要云凭据，而「响应结构怎么解析」必须在没有凭据时也能验收。
 * 端口只负责发出请求并原样返回响应体，字段解析一律在 {@link TingwuService} 与
 * {@link TranscriptionParser} 里做，避免把契约判断散落在适配器里。
 */
public interface TingwuApi {

    /** CreateTask：提交离线转写，返回原始响应体 */
    Map<String, ?> createTask(String requestBodyJson);

    /** GetTaskInfo：查询任务，返回原始响应体 */
    Map<String, ?> getTask(String taskId);
}
