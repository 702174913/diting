package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.MediaKind;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.entity.UploadSession;
import com.diting.mapper.UploadSessionMapper;
import com.diting.service.storage.AudioFormatSniffer;
import com.diting.service.storage.ImageFormatSniffer;
import com.diting.service.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 上传会话（开发文档 §5.1、§6.2）。
 *
 * 协议：服务端生成 owner 绑定的 objectKey → 签发预签名 → 客户端**原始字节 PUT** → complete 校验。
 * 校验只认服务端测得的事实：对象存在、长度、文件头嗅探出的真实格式；
 * 客户端声明的文件名/大小只用于预检，不作为事实写入。
 *
 * 时长目前无法离线测量（不引入未授权的媒体库、宿主也可能没有 ffprobe），
 * 因此上传阶段不写 duration_ms，等 S05 用听悟返回的真实时长回填并交叉校验。
 */
@Service
public class UploadSessionService {

    public static final String STATUS_SIGNED = "SIGNED";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_CONSUMED = "CONSUMED";

    /** 嗅探文件头需要的字节数 */
    private static final int SNIFF_BYTES = 16;

    private final UploadSessionMapper uploadSessionMapper;
    private final ObjectStorage objectStorage;
    private final DitingProperties props;
    private final com.diting.mapper.UserMapper userMapper;
    private Clock clock = Clock.systemUTC();

    public UploadSessionService(UploadSessionMapper uploadSessionMapper, ObjectStorage objectStorage,
                                DitingProperties props, com.diting.mapper.UserMapper userMapper) {
        this.uploadSessionMapper = uploadSessionMapper;
        this.objectStorage = objectStorage;
        this.props = props;
        this.userMapper = userMapper;
    }

    /** 仅测试使用：注入可控时钟 */
    void useClock(Clock testClock) {
        this.clock = testClock;
    }

    private void assertActiveUser(Long userId) {
        com.diting.entity.User user = userMapper.lockById(userId);
        if (user == null || user.getErasedAt() != null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号已注销，请重新登录");
        }
    }

    /**
     * 签发上传会话。
     * 同意记录（consentVersion）必填：没有同意就不给签名，前端绕不过去。
     * 媒体类型按扩展名先判定（S16）：图片走图片白名单与 10MB 上限，音频按原白名单；
     * complete 时再用文件头复核，声明与事实不符一律拒绝。
     */
    @Transactional
    public SignResult sign(Long userId, String filename, String consentVersion, Long declaredSize) {
        String normalizedName = sanitizeFilename(filename);
        String extension = extensionOf(normalizedName);
        String audioFormat = normalizeAudio(extension);
        String imageFormat = ImageFormatSniffer.normalize(extension);
        boolean image = allowedImageFormats().contains(imageFormat) && !allowedFormats().contains(audioFormat);
        if (!image && !allowedFormats().contains(audioFormat)) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "不支持的文件类型：" + extension + "（音频 " + String.join("/", allowedFormats())
                    + "；图片 " + String.join("/", allowedImageFormats()) + "）");
        }
        if (consentVersion == null || consentVersion.isBlank()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请先同意处理条款");
        }
        String kind = image ? MediaKind.IMAGE.name() : MediaKind.AUDIO.name();
        long maxSize = maxSizeFor(kind);
        if (declaredSize != null && declaredSize > maxSize) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                (image ? "图片" : "文件") + "超过大小上限（" + (maxSize / 1024 / 1024) + "MB）");
        }

        // 与抹除共用用户行锁：拦截器的早期复核不能阻止「鉴权后、签名插入前」的并发注销。
        assertActiveUser(userId);
        LocalDateTime now = BusinessTime.now(clock);
        String format = image ? imageFormat : audioFormat;
        String objectKey = objectKeyFor(userId, kind, format);
        // 先签名再落库：signed_until 是 NOT NULL，必须在插入时就带上
        ObjectStorage.PresignedRequest presigned = objectStorage.presignPut(objectKey, signTtl(),
            contentTypeOf(null, format));

        UploadSession session = new UploadSession();
        session.setUserId(userId);
        session.setObjectKey(objectKey);
        session.setStatus(STATUS_SIGNED);
        session.setMediaKind(kind);
        session.setFilename(normalizedName);
        session.setConsentVersion(consentVersion.trim());
        session.setConsentedAt(now);
        session.setCreatedAt(now);
        session.setSignedUntil(LocalDateTime.ofInstant(presigned.expiresAt(), BusinessTime.ZONE));
        session.setCleanupAfter(now.plusHours(props.getMedia().getSessionTtlHours()));
        uploadSessionMapper.insert(session);

        return new SignResult(session.getId(), session.getObjectKey(), presigned, session.getCleanupAfter());
    }

    /**
     * 刷新签名：同一个会话、同一个 objectKey，只换一张新的预签名。
     * 已消费的会话不能再刷（防止把已登记任务的对象再传一次）。
     * P1-2 修复（TOCTOU）：已校验（VERIFIED）的会话同样不能再刷——
     * 同 key 覆盖会让 complete 时验过的内容被替换，submit 用的就是替换后的字节。
     * 签名过期需要重传时，调用方重新 sign 一个新会话（新 objectKey）。
     */
    @Transactional
    public SignResult refresh(Long userId, Long uploadId) {
        assertActiveUser(userId);
        UploadSession session = lockOwned(userId, uploadId);
        if (STATUS_CONSUMED.equals(session.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "该上传已完成登记，不能重复使用");
        }
        if (STATUS_VERIFIED.equals(session.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "该上传已通过校验，不能重复上传；如需重传请重新发起上传");
        }
        return presign(session, BusinessTime.now(clock));
    }

    /** 上传完成校验：对象存在、非空、不超限、真实格式与扩展名一致 */
    @Transactional
    public UploadSession complete(Long userId, Long uploadId) {
        assertActiveUser(userId);
        UploadSession session = lockOwned(userId, uploadId);
        if (STATUS_VERIFIED.equals(session.getStatus())) {
            return session;
        }
        if (STATUS_CONSUMED.equals(session.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "该上传已用于登记任务");
        }

        ObjectStorage.ObjectMeta meta = objectStorage.head(session.getObjectKey());
        if (meta == null) {
            throw new BizException(ResultCode.UNPROCESSABLE, "对象不存在，请重新上传");
        }
        if (meta.size() <= 0) {
            throw new BizException(ResultCode.UNPROCESSABLE, "上传的文件为空");
        }
        boolean image = MediaKind.IMAGE.name().equals(session.getMediaKind());
        long maxSize = maxSizeFor(session.getMediaKind());
        if (meta.size() > maxSize) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                (image ? "图片" : "文件") + "超过大小上限（" + (maxSize / 1024 / 1024) + "MB）");
        }

        byte[] head = objectStorage.readPrefix(session.getObjectKey(), SNIFF_BYTES);
        String detected = image ? ImageFormatSniffer.sniff(head) : AudioFormatSniffer.sniff(head);
        if (detected == null) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                image ? "这看起来不是图片（支持 JPG/PNG/WebP/GIF/BMP/TIFF；手机 HEIC 需先转成 JPG）"
                    : "无法识别的音频格式，请确认导出的是原始录音");
        }
        List<String> allowed = image ? allowedImageFormats() : allowedFormats();
        if (!allowed.contains(detected)) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "实际格式为 " + detected + "，不在支持范围内");
        }
        String declared = image
            ? ImageFormatSniffer.normalize(extensionOf(session.getFilename()))
            : normalizeAudio(extensionOf(session.getFilename()));
        if (!detected.equals(declared)) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "文件实际格式为 " + detected + "，与文件名后缀 " + declared + " 不一致");
        }

        session.setStatus(STATUS_VERIFIED);
        session.setSizeBytes(meta.size());
        session.setMediaFormat(detected);
        // ETag 作为对象内容指纹，便于后续与云端比对是否被替换
        session.setContentHash(meta.etag());
        uploadSessionMapper.updateById(session);
        return session;
    }

    /**
     * 登记任务时取可信对象：只认本人的、已通过校验的会话。
     * P1-2 修复（TOCTOU 纵深）：校验后到登记前对象可能被同 key 覆盖，
     * 这里用 complete 时记录的 contentHash（ETag）与当前对象比对，
     * 不一致说明校验后内容被替换，拒绝登记（要求重新上传校验）。
     */
    @Transactional
    public UploadSession consumeForSubmit(Long userId, Long uploadId) {
        UploadSession session = lockOwned(userId, uploadId);
        if (!STATUS_VERIFIED.equals(session.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "请先完成上传校验");
        }
        if (session.getContentHash() != null) {
            ObjectStorage.ObjectMeta meta = objectStorage.head(session.getObjectKey());
            if (meta == null || !session.getContentHash().equals(meta.etag())) {
                throw new BizException(ResultCode.CONFLICT, "上传内容已被改动，请重新上传并校验");
            }
        }
        if (uploadSessionMapper.consumeVerified(uploadId, userId) != 1) {
            throw new BizException(ResultCode.CONFLICT, "该上传已被其他请求登记，请刷新后重试");
        }
        session.setStatus(STATUS_CONSUMED);
        return session;
    }

    private UploadSession lockOwned(Long userId, Long uploadId) {
        UploadSession session = uploadSessionMapper.lockById(uploadId);
        if (session == null || !java.util.Objects.equals(session.getUserId(), userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "上传记录不存在");
        }
        return session;
    }

    public UploadSession requireOwned(Long userId, Long uploadId) {
        UploadSession session = uploadSessionMapper.selectById(uploadId);
        if (session == null || !session.getUserId().equals(userId)) {
            // 他人的上传会话一律 404，不泄露存在性
            throw new BizException(ResultCode.NOT_FOUND, "上传记录不存在");
        }
        return session;
    }

    private SignResult presign(UploadSession session, LocalDateTime now) {
        ObjectStorage.PresignedRequest presigned =
            objectStorage.presignPut(session.getObjectKey(), signTtl(), contentTypeOf(session.getMediaFormat(),
                extensionOf(session.getFilename())));
        session.setSignedUntil(LocalDateTime.ofInstant(presigned.expiresAt(), BusinessTime.ZONE));
        uploadSessionMapper.updateById(session);
        return new SignResult(session.getId(), session.getObjectKey(), presigned, session.getCleanupAfter());
    }

    private Duration signTtl() {
        return Duration.ofMinutes(props.getMedia().getSignTtlMinutes());
    }

    /** objectKey 由服务端生成并带 owner 前缀，客户端无法控制路径；图片与录音分目录便于生命周期清理 */
    private static String objectKeyFor(Long userId, String kind, String format) {
        String prefix = MediaKind.IMAGE.name().equals(kind) ? "images" : "recordings";
        return "%s/%d/%s.%s".formatted(prefix, userId, UUID.randomUUID(), format);
    }

    private List<String> allowedFormats() {
        return normalizeList(props.getMedia().getAllowedFormats());
    }

    private List<String> allowedImageFormats() {
        return normalizeList(props.getMedia().getAllowedImageFormats()).stream()
            .map(ImageFormatSniffer::normalize)
            .distinct()
            .toList();
    }

    /** 音频扩展名归一（同一格式不同写法按一种处理） */
    private static String normalizeAudio(String extension) {
        return extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeList(List<String> values) {
        return values.stream()
            .map(f -> f.trim().toLowerCase(Locale.ROOT))
            .filter(f -> !f.isEmpty())
            .toList();
    }

    /** 单对象大小上限：图片与音频各有边界（图片按 OCR 官方 10MB） */
    private long maxSizeFor(String kind) {
        return MediaKind.IMAGE.name().equals(kind)
            ? props.getMedia().getMaxImageBytes()
            : props.getMedia().getMaxSizeBytes();
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "文件名不能为空");
        }
        String name = filename.trim();
        // 只保留最后一段，防止用 ../../ 之类的路径当文件名
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = slash >= 0 ? name.substring(slash + 1) : name;
        if (name.length() > 255) {
            name = name.substring(name.length() - 255);
        }
        return name;
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
            ? ""
            : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String contentTypeOf(String detected, String extension) {
        String format = ImageFormatSniffer.normalize(detected == null || detected.isBlank()
            ? extension : detected);
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a", "mp4" -> "audio/mp4";
            case "amr" -> "audio/amr";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /** 签发结果：uploadId/objectKey 由服务端生成，客户端只按 method+headers 发送原始字节 */
    public record SignResult(Long uploadId, String objectKey, ObjectStorage.PresignedRequest presigned,
                             LocalDateTime cleanupAfter) {
    }
}
