package com.diting.service.tingwu;

import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.Client;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.Common;
import com.aliyun.teautil.models.RuntimeOptions;
import com.diting.config.DitingProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 阿里云实现（通义听悟 2023-09-30，通用 callApi）。
 *
 * 依赖选择：保持 tingwu20230930 2.0.0 不升级——强类型模型缺 Result 字段，
 * 但我们走通用 callApi 拿原始响应体（开发文档 §2 已记录该结论），
 * 升级 SDK 不会带来字段收益，反而引入回归风险。
 */
@Slf4j
public class AliyunTingwuApi implements TingwuApi {

    private static final String VERSION = "2023-09-30";
    private static final String ENDPOINT = "tingwu.cn-beijing.aliyuncs.com";

    private final Client client;

    public AliyunTingwuApi(DitingProperties props) throws Exception {
        DitingProperties.Aliyun aliyun = props.getAliyun();
        this.client = new Client(new Config()
            .setAccessKeyId(aliyun.getAccessKeyId())
            .setAccessKeySecret(aliyun.getAccessKeySecret())
            .setEndpoint(ENDPOINT));
    }

    @Override
    public Map<String, ?> createTask(String requestBodyJson) {
        try {
            Params params = new Params()
                .setAction("CreateTask")
                .setVersion(VERSION)
                .setProtocol("HTTPS")
                .setPathname("/openapi/tingwu/v2/tasks")
                .setMethod("PUT")
                .setAuthType("AK")
                .setStyle("ROA")
                .setReqBodyType("json")
                .setBodyType("json");
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            Map<String, String> query = new HashMap<>();
            query.put("type", "offline");
            OpenApiRequest request = new OpenApiRequest()
                .setHeaders(headers)
                .setQuery(query)
                .setBody(Common.parseJSON(requestBodyJson));
            return responseBody(client.callApi(params, request, new RuntimeOptions()));
        }
        catch (TeaException e) {
            // 不记录请求体、文件签名 URL、云端原始错误文本或密钥；只留排查用的安全字段。
            log.warn("听悟 CreateTask 调用异常 status={} code={} requestId={}", e.getStatusCode(),
                safeField(e.getCode()), safeField(e.getData() == null ? null : e.getData().get("RequestId")));
            if (definitelyRejected(e.getStatusCode())) {
                throw new CloudSubmitRejectedException(e.getCode());
            }
            throw new IllegalStateException("听悟 CreateTask 提交结果不确定", e);
        }
        catch (Exception e) {
            log.warn("听悟 CreateTask 调用异常 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("听悟 CreateTask 提交结果不确定", e);
        }
    }

    @Override
    public Map<String, ?> getTask(String taskId) {
        try {
            return responseBody(client.callApi(getTaskParams(taskId), new OpenApiRequest(), new RuntimeOptions()));
        }
        catch (Exception e) {
            throw new IllegalStateException("听悟 GetTaskInfo 调用失败", e);
        }
    }

    /** 泛化调用的 Params 必须声明响应类型；缺 bodyType 时 SDK 会在发送前抛 ValidateException。 */
    static Params getTaskParams(String taskId) {
        return new Params()
            .setAction("GetTaskInfo")
            .setVersion(VERSION)
            .setProtocol("HTTPS")
            .setPathname("/openapi/tingwu/v2/tasks/" + taskId)
            .setMethod("GET")
            .setAuthType("AK")
            .setStyle("ROA")
            .setReqBodyType("json")
            .setBodyType("json");
    }

    /** 408/409/425/429 和全部 5xx 均可能已被受理，必须留在不确定对账队列。 */
    static boolean definitelyRejected(Integer status) {
        return status != null && status >= 400 && status < 500
            && status != 408 && status != 409 && status != 425 && status != 429;
    }

    /** 泛化调用返回 {body, headers, statusCode}，业务 Code/Data 在 body 中。 */
    static Map<String, ?> responseBody(Map<String, ?> response) {
        if (response == null || !(response.get("body") instanceof Map<?, ?> body)) {
            throw new IllegalStateException("听悟 API 响应缺少 JSON body");
        }
        @SuppressWarnings("unchecked")
        Map<String, ?> result = (Map<String, ?>) body;
        return result;
    }

    private static String safeField(Object value) {
        if (value == null) {
            return "-";
        }
        String safe = String.valueOf(value).replaceAll("[^a-zA-Z0-9._-]", "");
        return safe.substring(0, Math.min(128, safe.length()));
    }
}
