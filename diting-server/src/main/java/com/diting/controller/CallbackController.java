package com.diting.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.service.RecognitionTaskService;
import com.diting.service.tingwu.JsonNodeReader;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通义听悟异步回调。
 *
 * 官方契约（2026-09-27 核对，来源见 docs/开发文档.md §7.3 引用的两份帮助文档）：
 * - **回调地址在听悟管控台按项目配置**（回调方式 = HTTP post 协议），CreateTask 请求里只有
 *   {@code Input.ProgressiveCallbacksEnabled=true}，没有任何 URL 参数——所以本服务的
 *   {@code diting.aliyun.tingwu.callback-path} 只用于启动日志提示"管控台该填什么"，不参与请求；
 * - 回调体：{@code {"Code":"0","Data":{"TaskId":…,"TaskStatus":"ONGOING|COMPLETED|FAILED","TaskKey":…,"Result":{…}},…}}
 *   —— **TaskId 在 Data 里，不在顶层**；
 * - 管控台保存回调地址时会先发一条连通性检查：{@code {"Code":"0","Data":{"Test":"CheckSyncConf<时间戳>"}}}；
 * - 接收方必须在 **5 秒内返回 HTTP 200**，否则会被判为配置失败或触发重投；
 * - 官方文档**没有**回调验签字段（S14 记录的"验签字段未确认"即此）：
 *   因此这里不伪造签名契约，改用**路径共享令牌** + 任务归属校验。
 *
 * 安全模型：
 * - 回调永远只做**唤醒**：不采信请求里的 TaskStatus/Result，状态与结果一律由服务端主动查询判定
 *   （所以伪造回调的代价只是一次空轮询，不会污染数据）；
 * - TaskId 必须命中库里的任务，且任务未被删除（墓碑优先，迟到回调不能复活数据）；
 * - 配置了 callbackToken 就必须带对路径令牌（否则 401）；未配置时按 IP 限流。
 */
@Slf4j
@RestController
@RequestMapping("/api/callback")
public class CallbackController {

    private final RecognitionTaskMapper taskMapper;
    private final RecognitionTaskService taskService;
    private final DitingProperties props;

    /** 未配令牌时的最小限流：IP → 当前分钟窗口计数（防回调轰炸空转管线） */
    private final ConcurrentHashMap<String, AtomicLong> minuteHits = new ConcurrentHashMap<>();
    private volatile long minuteWindow;

    public CallbackController(RecognitionTaskMapper taskMapper, RecognitionTaskService taskService,
                              DitingProperties props) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.props = props;
        this.minuteWindow = System.currentTimeMillis() / 60000;
    }

    /**
     * 启动时把"管控台该填什么"打出来：这是回调能生效的唯一人工步骤，
     * 不写出来就只能靠猜（历史上 callbackPath 就是一条没人用的死配置）。
     */
    @PostConstruct
    void logCallbackAddress() {
        var tingwu = props.getAliyun().getTingwu();
        String path = callbackPath();
        String token = callbackToken();
        String suffix = token.isBlank() ? "" : "/<callback-token>";
        String base = tingwu.getPublicBaseUrl() == null ? "" : tingwu.getPublicBaseUrl().trim();
        if (base.isBlank()) {
            log.warn("听悟回调未配置对外地址：请在听悟管控台（项目 → 回调方式 = HTTP post）填入 "
                + "https://<你的公网域名>{}{}，并设置 DITING_PUBLIC_BASE_URL 以便这里提示实际地址", path, suffix);
            return;
        }
        log.info("听悟回调地址（管控台按项目配置，必须公网可达且 5 秒内返回 200）：{}{}{}", trimTrailingSlash(base), path, suffix);
    }

    @PostMapping({"/tingwu", "/tingwu/{token}"})
    public R<Void> tingwu(@RequestBody(required = false) Map<String, Object> payload,
                          @PathVariable(value = "token", required = false) String pathToken,
                          HttpServletRequest request) {
        assertCaller(pathToken, request);
        if (payload == null || payload.isEmpty()) {
            // 空体也回 200：回调只做唤醒，没有需要纠正的副作用
            log.warn("听悟回调体为空，已忽略");
            return R.ok();
        }
        JsonNodeReader reader = JsonNodeReader.of(payload);
        // 管控台保存回调地址时的连通性检查：不查库、不唤醒，必须尽快 200
        if (reader.text("Data.Test") != null) {
            log.info("听悟回调连通性检查通过");
            return R.ok();
        }
        // TaskId 在 Data 下（顶层兜底只为手工 curl 调试方便）
        String remoteTaskId = firstNonBlank(reader.text("Data.TaskId"), reader.text("TaskId"));
        if (remoteTaskId == null) {
            log.warn("听悟回调缺少 Data.TaskId，已忽略：字段={}", payload.keySet());
            return R.ok();
        }
        RecognitionTask task = taskMapper.selectOne(new LambdaQueryWrapper<RecognitionTask>()
            .eq(RecognitionTask::getTaskId, remoteTaskId));
        if (task == null) {
            // 不是本服务的任务（同主账号可能配了多个项目）：只记日志，不泄露存在性
            log.info("听悟回调的任务不在本库，已忽略：taskId={}", remoteTaskId);
            return R.ok();
        }
        if (task.getDeletedAt() != null) {
            // 墓碑优先：已删除的任务不接受任何回调，避免迟到回调把数据救回来
            log.info("已删除任务的回调被忽略：taskId={}", remoteTaskId);
            return R.ok();
        }
        // 只做唤醒：回调里的 TaskStatus/Result 一律不采信，状态由服务端主动查询后判定
        boolean woke = taskService.wakeUp(task.getId());
        log.info("听悟回调唤醒：taskId={} 本地任务={} 回调状态={} 已置为待领取={}",
            remoteTaskId, task.getId(), reader.text("Data.TaskStatus"), woke);
        return R.ok();
    }

    /**
     * 来源约束：
     * - 配了令牌：路径末段必须一致（常数时间比较），不带令牌的裸地址一律 401；
     * - 没配令牌：按 IP 限流（默认 60 次/分钟）。
     *   注意：同一个听悟项目下所有任务的回调来自同一侧，任务量大时这个上限会拦住合法回调
     *   ——生产应配置 callbackToken（配了就不再按 IP 限流），或调大 callbackRatePerMinute。
     */
    private void assertCaller(String pathToken, HttpServletRequest request) {
        String expected = callbackToken();
        if (!expected.isBlank()) {
            if (pathToken == null || !constantTimeEquals(expected, pathToken.trim())) {
                throw new BizException(ResultCode.UNAUTHORIZED, "回调令牌无效");
            }
            return;
        }
        assertRateLimit(clientIp(request));
    }

    /** 未配令牌时的最小限流：单 IP 每窗口 N 次（只影响回调入口） */
    private void assertRateLimit(String ip) {
        int limit = Math.max(1, props.getAliyun().getTingwu().getCallbackRatePerMinute());
        long window = System.currentTimeMillis() / 60000;
        if (window != minuteWindow) {
            synchronized (this) {
                if (window != minuteWindow) {
                    minuteWindow = window;
                    minuteHits.clear();
                }
            }
        }
        long hits = minuteHits.computeIfAbsent(ip, k -> new AtomicLong()).incrementAndGet();
        if (hits > limit) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "回调过于频繁");
        }
    }

    private String callbackToken() {
        String token = props.getAliyun().getTingwu().getCallbackToken();
        return token == null ? "" : token.trim();
    }

    private String callbackPath() {
        String path = props.getAliyun().getTingwu().getCallbackPath();
        return path == null || path.isBlank() ? "/api/callback/tingwu" : path.trim();
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static String clientIp(HttpServletRequest request) {
        return com.diting.config.ClientAddress.of(request);
    }
}
