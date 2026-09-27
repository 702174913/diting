package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.entity.UploadSession;
import com.diting.entity.User;
import com.diting.mapper.UploadSessionMapper;
import com.diting.mapper.UserMapper;
import com.diting.service.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S04 上传会话：签发、刷新、完成校验、归属与同意。
 * 对象存储用真实的 InMemoryObjectStorage（不是 mock），因此大小/格式/存在性判定走的是真代码。
 */
class UploadSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T03:00:00Z");
    private static final long MAX_SIZE = 1024 * 1024;

    private final Map<Long, UploadSession> sessions = new HashMap<>();
    private InMemoryObjectStorage storage;
    private UploadSessionService service;
    private UploadSessionMapper mapper;
    private UserMapper users;
    private User activeUser;

    @BeforeEach
    void setUp() {
        sessions.clear();
        storage = new InMemoryObjectStorage("http://memory-storage.test");
        mapper = mock(UploadSessionMapper.class);
        users = mock(UserMapper.class);
        activeUser = new User();
        activeUser.setId(7L);
        when(users.lockById(7L)).thenReturn(activeUser);
        User anotherUser = new User();
        anotherUser.setId(8L);
        when(users.lockById(8L)).thenReturn(anotherUser);
        when(mapper.insert(any(UploadSession.class))).thenAnswer(inv -> {
            UploadSession row = inv.getArgument(0);
            row.setId(100L);
            sessions.put(row.getId(), row);
            return 1;
        });
        when(mapper.updateById(any(UploadSession.class))).thenAnswer(inv -> {
            UploadSession row = inv.getArgument(0);
            sessions.put(row.getId(), row);
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(inv -> sessions.get(inv.getArgument(0)));
        when(mapper.lockById(any())).thenAnswer(inv -> sessions.get(inv.getArgument(0)));
        when(mapper.consumeVerified(any(), any())).thenAnswer(inv -> {
            UploadSession row = sessions.get(inv.getArgument(0));
            if (row == null || !"VERIFIED".equals(row.getStatus()) || !row.getUserId().equals(inv.getArgument(1))) return 0;
            row.setStatus("CONSUMED");
            return 1;
        });

        DitingProperties props = new DitingProperties();
        props.getMedia().setMaxSizeBytes(MAX_SIZE);
        service = new UploadSessionService(mapper, storage, props, users);
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static byte[] m4aBytes(int totalLength) {
        byte[] content = new byte[totalLength];
        byte[] head = "0000ftypM4A ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    /** 最小 PNG 头（含 IHDR 宽高）：尺寸预检只读文件头，不需要真实像素数据 */
    private static byte[] png(int width, int height) {
        byte[] b = new byte[24];
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        b[4] = 0x0D;
        b[5] = 0x0A;
        b[6] = 0x1A;
        b[7] = 0x0A;
        b[12] = 'I';
        b[13] = 'H';
        b[14] = 'D';
        b[15] = 'R';
        b[16] = (byte) (width >>> 24);
        b[17] = (byte) (width >>> 16);
        b[18] = (byte) (width >>> 8);
        b[19] = (byte) width;
        b[20] = (byte) (height >>> 24);
        b[21] = (byte) (height >>> 16);
        b[22] = (byte) (height >>> 8);
        b[23] = (byte) height;
        return b;
    }

    private UploadSessionService.SignResult sign(Long userId, String filename) {
        return service.sign(userId, filename, "v1", 1024L);
    }

    @Test
    void erasedUserCannotCreateOrRefreshUploadAfterAuthenticationRace() {
        var signed = sign(7L, "a.m4a");
        activeUser.setErasedAt(LocalDateTime.now());
        assertThatThrownBy(() -> sign(7L, "b.m4a"))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode()).isEqualTo(ResultCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.refresh(7L, signed.uploadId()))
            .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.complete(7L, signed.uploadId()))
            .isInstanceOf(BizException.class);
        assertThat(sessions).hasSize(1);
    }

    @Test
    @DisplayName("UPLOAD-01 签发结果：PUT + owner 前缀 objectKey + 有效期，且不带 multipart 语义")
    void signShape() {
        UploadSessionService.SignResult result = sign(7L, "通话录音.m4a");

        assertThat(result.presigned().method()).isEqualTo("PUT");
        assertThat(result.objectKey()).startsWith("recordings/7/").endsWith(".m4a");
        assertThat(result.presigned().url()).contains(result.objectKey());
        // 预签名有效期由存储实现用真实时钟签发，这里断言配置的 TTL 被正确应用
        assertThat(result.presigned().expiresAt())
            .isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(16 * 60));
        assertThat(result.cleanupAfter()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneId.of("Asia/Shanghai")).plusHours(24));
        assertThat(sessions.get(100L).getSignedUntil()).isNotNull();
        assertThat(sessions.get(100L).getStatus()).isEqualTo(UploadSessionService.STATUS_SIGNED);
    }

    @Test
    void onlyOneConsumerCanRegisterAnUpload() {
        var signed = sign(7L, "a.m4a");
        storage.put(signed.objectKey(), m4aBytes(64), "audio/mp4");
        service.complete(7L, signed.uploadId());
        service.consumeForSubmit(7L, signed.uploadId());
        assertThatThrownBy(() -> service.consumeForSubmit(7L, signed.uploadId())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.refresh(7L, signed.uploadId())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.complete(7L, signed.uploadId())).isInstanceOf(BizException.class);
        assertThat(sessions.get(signed.uploadId()).getStatus()).isEqualTo("CONSUMED");
    }

    @Test
    void consumeChecksAffectedRows() {
        var signed = sign(7L, "a.m4a");
        storage.put(signed.objectKey(), m4aBytes(64), "audio/mp4");
        service.complete(7L, signed.uploadId());
        org.mockito.Mockito.doReturn(0).when(mapper).consumeVerified(any(), any());
        assertThatThrownBy(() -> service.consumeForSubmit(7L, signed.uploadId())).isInstanceOf(BizException.class);
        assertThat(sessions.get(signed.uploadId()).getStatus()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("UPLOAD-08 未同意条款：拒绝签发")
    void consentRequired() {
        assertThatThrownBy(() -> service.sign(7L, "a.m4a", "  ", 10L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
    }

    @Test
    @DisplayName("扩展名不在允许列表 / 超大声明：签发阶段就拒绝")
    void precheck() {
        assertThatThrownBy(() -> sign(7L, "a.exe")).isInstanceOf(BizException.class)
            .hasMessageContaining("不支持的文件类型");
        assertThatThrownBy(() -> service.sign(7L, "a.m4a", "v1", MAX_SIZE + 1))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("超过大小上限");
    }

    @Test
    @DisplayName("文件名里的路径被剥掉，objectKey 始终由服务端生成")
    void filenameIsSanitized() {
        ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
        sign(7L, "../../etc/passwd.m4a");

        assertThat(sessions.get(100L).getFilename()).isEqualTo("passwd.m4a");
        assertThat(sessions.get(100L).getObjectKey()).doesNotContain("..");
        assertThat(captor.getAllValues()).isEmpty();
    }

    @Test
    @DisplayName("UPLOAD-03 对象不存在 / 空文件 / 超限：complete 一律拒绝")
    void completeRejectsBadObjects() {
        sign(7L, "a.m4a");
        assertThatThrownBy(() -> service.complete(7L, 100L))
            .isInstanceOf(BizException.class).hasMessageContaining("对象不存在");

        storage.putObject(sessions.get(100L).getObjectKey(), new byte[0], "audio/mp4");
        assertThatThrownBy(() -> service.complete(7L, 100L)).hasMessageContaining("空");

        storage.putObject(sessions.get(100L).getObjectKey(), m4aBytes((int) MAX_SIZE + 1), "audio/mp4");
        assertThatThrownBy(() -> service.complete(7L, 100L)).hasMessageContaining("超过大小上限");
    }

    @Test
    @DisplayName("UPLOAD-02 伪扩展名：内容与后缀不一致时拒绝（不看扩展名看字节）")
    void fakeExtensionRejected() {
        sign(7L, "伪装.m4a");
        storage.putObject(sessions.get(100L).getObjectKey(),
            "ID3fake-mp3-content".getBytes(StandardCharsets.US_ASCII), "audio/mpeg");

        assertThatThrownBy(() -> service.complete(7L, 100L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("实际格式为 mp3");
    }

    @Test
    @DisplayName("正常完成：状态 VERIFIED，大小/格式/指纹按服务端测得的事实写入")
    void completeHappyPath() {
        sign(7L, "正常.m4a");
        byte[] content = m4aBytes(2048);
        storage.putObject(sessions.get(100L).getObjectKey(), content, "audio/mp4");

        UploadSession session = service.complete(7L, 100L);

        assertThat(session.getStatus()).isEqualTo(UploadSessionService.STATUS_VERIFIED);
        assertThat(session.getSizeBytes()).isEqualTo(2048L);
        assertThat(session.getMediaFormat()).isEqualTo("m4a");
        assertThat(session.getContentHash()).isNotBlank();
        // 重复 complete 幂等
        assertThat(service.complete(7L, 100L).getStatus()).isEqualTo(UploadSessionService.STATUS_VERIFIED);
    }

    @Test
    @DisplayName("UPLOAD-07 图片尺寸超出 OCR 受理范围：complete 直接拒（省掉一次必然失败的付费调用）")
    void oversizedImageRejectedAtComplete() {
        sign(7L, "大图.png");
        storage.putObject(sessions.get(100L).getObjectKey(), png(9000, 9000), "image/png");

        assertThatThrownBy(() -> service.complete(7L, 100L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("尺寸超出识别范围");
    }

    @Test
    @DisplayName("UPLOAD-08 尺寸合规的图片正常通过校验")
    void normalSizedImagePassesComplete() {
        sign(7L, "图.png");
        storage.putObject(sessions.get(100L).getObjectKey(), png(1200, 800), "image/png");

        assertThat(service.complete(7L, 100L).getMediaFormat()).isEqualTo("png");
    }

    @Test
    @DisplayName("UPLOAD-06 他人的上传会话：404，不泄露存在性")
    void ownershipEnforced() {
        sign(7L, "a.m4a");

        assertThatThrownBy(() -> service.complete(8L, 100L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
        assertThatThrownBy(() -> service.refresh(8L, 100L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.consumeForSubmit(8L, 100L)).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未通过 complete 的会话不能用于登记任务")
    void submitRequiresVerifiedSession() {
        sign(7L, "a.m4a");

        assertThatThrownBy(() -> service.consumeForSubmit(7L, 100L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("刷新签名：objectKey 不变、只换新签名；已消费的会话不能再刷")
    void refreshKeepsObjectKey() {
        sign(7L, "a.m4a");
        String firstKey = sessions.get(100L).getObjectKey();

        UploadSessionService.SignResult refreshed = service.refresh(7L, 100L);

        assertThat(refreshed.objectKey()).isEqualTo(firstKey);
        assertThat(refreshed.uploadId()).isEqualTo(100L);

        storage.putObject(firstKey, m4aBytes(1024), "audio/mp4");
        service.complete(7L, 100L);
        service.consumeForSubmit(7L, 100L);
        assertThatThrownBy(() -> service.refresh(7L, 100L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }
}
