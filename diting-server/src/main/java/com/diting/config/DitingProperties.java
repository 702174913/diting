package com.diting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "diting")
public class DitingProperties {

    private Jwt jwt = new Jwt();
    private Wechat wechat = new Wechat();
    private Aliyun aliyun = new Aliyun();
    private Admin admin = new Admin();
    private Media media = new Media();
    private Storage storage = new Storage();
    private Pipeline pipeline = new Pipeline();
    private Export export = new Export();
    private Retention retention = new Retention();

    /**
     * 媒体边界（开发文档 §11「媒体」）：真实支持范围由 POC 决定，
     * 这里给保守默认值，超限一律拒绝而不是尝试上传。
     */
    @Data
    public static class Media {
        /** 允许的音频容器（按文件头嗅探出的真实格式判断，不看扩展名） */
        private List<String> allowedFormats = new ArrayList<>(List.of("m4a", "mp3", "wav", "amr", "aac", "flac"));
        /** 单文件大小上限（字节）；微信大文件内存与超时需真机评估后调整 */
        private long maxSizeBytes = 50L * 1024 * 1024;
        /** 允许的图片格式（S16）：与阿里云 OCR 受理范围对齐，HEIC 不在其中 */
        private List<String> allowedImageFormats =
            new ArrayList<>(List.of("jpg", "jpeg", "png", "webp", "bmp", "gif", "tiff", "tif"));
        /** 单张图片大小上限（字节）：官方文档写「二进制不超过 10MB」 */
        private long maxImageBytes = 10L * 1024 * 1024;
        /** 一次提交最多几张图片（用户需求：6 张） */
        private int maxImagesPerTask = 6;
        /** 预签名有效期（分钟） */
        private int signTtlMinutes = 15;
        /** 上传会话留存时长（小时）：未消费的孤立对象在这之后可被清理（清理作业属 S13） */
        private int sessionTtlHours = 24;
        /** 图片/资产回显地址有效期（分钟）：比导出产物短，够打开一张图即可 */
        private int assetUrlTtlMinutes = 15;
    }

    /** 对象存储类型：auto（按凭据判断）/ oss / memory（仅测试） */
    @Data
    public static class Storage {
        private String type = "auto";
    }

    /**
     * 导出（开发文档 §9、§11「PDF/ZIP」）：固定模板、字体、并发/体积与产物 TTL。
     * font-path 需指向**持有合法授权**的中文字体（TTF/TTC）；缺失时导出返回 503，不产出空 PDF。
     */
    @Data
    public static class Export {
        private boolean enabled = true;
        /** 产物保留时长（小时） */
        private int artifactTtlHours = 24;
        /** 下载链接有效期（分钟） */
        private int downloadTtlMinutes = 15;
        /** 单次导出产物体积上限（字节） */
        private long maxArtifactBytes = 64L * 1024 * 1024;
        /** 每轮处理的作业数 */
        private int batchSize = 3;
        /** 导出渲染/上传租约；超时的 RUNNING 由下一轮重新领取 */
        private int leaseSeconds = 300;
        private int maxAttempts = 3;
        private int retryBackoffSeconds = 30;
        /** 调度间隔（毫秒） */
        private long tickIntervalMs = 3000;
        /** 中文字体路径；留空表示未配置（导出不可用） */
        private String fontPath = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf";
    }

    /** 保留期与清理（开发文档 §9、§13）：原件 30 天，文本/审计期限另议 */
    @Data
    public static class Retention {
        private boolean enabled = true;
        /** 原件保留天数 */
        private int audioDays = 30;
        /** 每轮清理数量 */
        private int batchSize = 20;
        /** 清理失败重试上限 */
        private int maxAttempts = 4;
        /** 清理作业租约（秒）：进程崩溃后超过它才会被重新领取 */
        private int leaseSeconds = 300;
        /** 退避基数（秒），第 n 次重试等待 n × 基数 */
        private int backoffSeconds = 60;
        /** 调度间隔（毫秒） */
        private long tickIntervalMs = 60000;
    }

    /** 后台作业管线：租约、退避与有限重试（开发文档 §11「作业」） */
    @Data
    public static class Pipeline {
        /** 是否启用调度（测试可关掉，手动 tick） */
        private boolean enabled = true;
        /** 每轮领取任务数 */
        private int batchSize = 5;
        /** 租约时长（秒）：进程崩溃后超过它才能被重领 */
        private int leaseSeconds = 60;
        /** 轮询远端状态的间隔（秒） */
        private int pollIntervalSeconds = 10;
        /** 失败后的基础退避（秒） */
        private int backoffSeconds = 30;
        /** 提交/对账的最大尝试次数，超过即停止重试等待人工处理 */
        private int maxAttempts = 5;
        /** 调度间隔（毫秒） */
        private long tickIntervalMs = 5000;
    }

    @Data
    public static class Jwt {
        private String secret;
        private long expireHours = 72;
    }

    /**
     * 后台账号初始化（仅首次启动、库中还没有 ADMIN 时生效）。
     * 值通过环境变量注入，配置文件里不写任何真实密码。
     */
    @Data
    public static class Admin {
        private String initUsername;
        private String initPassword;
    }

    @Data
    public static class Wechat {
        private String appid;
        private String secret;
    }

    @Data
    public static class Aliyun {
        private String accessKeyId;
        private String accessKeySecret;
        private Oss oss = new Oss();
        private Tingwu tingwu = new Tingwu();
        private Ocr ocr = new Ocr();

        @Data
        public static class Oss {
            private String endpoint;
            private String bucket;
        }

        /**
         * 文字识别（S16）：复用 AccessKey，但属独立产品——
         * 需单独开通「通用文字识别」并授予 RAM 策略 AliyunOCRFullAccess。
         */
        @Data
        public static class Ocr {
            /** auto（按凭据判断）/ stub（仅测试用桩客户端） */
            private String type = "auto";
            /** 服务接入点；默认杭州，跨地域时在配置里覆盖 */
            private String endpoint = "ocr-api.cn-hangzhou.aliyuncs.com";
        }

    @Data
    public static class Tingwu {
        private String appKey;
        /**
         * 回调路径。官方契约：回调地址在**听悟管控台按项目配置**（HTTP post），CreateTask 里
         * 没有 URL 参数，所以这里只用于启动日志提示「管控台该填什么」，不参与上传请求。
         */
        private String callbackPath = "/api/callback/tingwu";
        /** auto（按凭据判断）/ stub（仅测试用桩客户端） */
        private String type = "auto";
        /**
         * 回调路径共享令牌：管控台把地址配成 {publicBaseUrl}{callbackPath}/{token}。
         * 官方 HTTP 回调**没有**验签字段（2026-09-27 核对），故用路径令牌做来源约束；
         * 为空 = 未配置 = 只按 IP 限流（见 callbackRatePerMinute）。只走环境变量。
         */
        private String callbackToken = "";
        /**
         * 未配令牌时的回调限流（单 IP、每分锢）。同一项目下所有任务的回调同源，
         * 任务量大时这个上限会拦住合法回调：生产建议配 callbackToken（配了就不再按 IP 限流）。
         */
        private int callbackRatePerMinute = 60;
        /**
         * 对外可访问的基础地址（如 https://api.example.com），仅用于启动日志拼出
         * 「管控台要填的回调地址」；不参与签名或校验。
         */
        private String publicBaseUrl = "";
        /** 催收通话默认按双人分离；显式设为 0 则交由云端自动判断 */
        private Integer diarizationSpeakerCount = 2;
        /** 源语言：官方取值 cn/en/yue/ja/fspk（中文是 cn，不是 zh） */
        private String sourceLanguage = "cn";
        /**
         * 结果下载是否允许 http。
         * 官方示例里 Result.Transcription 出现过 http 链接；这里只对受信任后缀（默认阿里云域名）
         * 放开，其余仍强制 https，且私网/回环一律拒绝。POC 需确认实际返回值。
         */
        private boolean allowHttpResult = true;
        /** 允许 http 的域名后缀（逗号分隔） */
        private String httpResultHostSuffixes = "aliyuncs.com";
        }
    }
}
