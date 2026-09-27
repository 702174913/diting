package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.service.ocr.NotConfiguredOcrApi;
import com.diting.service.ocr.OcrApi;
import com.diting.service.ocr.OcrParser;
import com.diting.service.ocr.StubOcrApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 服务预检（S16 补强）：尺寸不合规的图在**调用云端之前**就被拒。
 *
 * 为什么值得单测：这里的漏网意味着一次必然失败的付费调用，还会按次数上限重试；
 * 而云端错误码是 illegalImageSize——排查成本高、收益为零。
 */
class OcrServiceTest {

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
        be(b, 16, width);
        be(b, 20, height);
        return b;
    }

    private static void be(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 24);
        b[at + 1] = (byte) (value >>> 16);
        b[at + 2] = (byte) (value >>> 8);
        b[at + 3] = (byte) value;
    }

    private static OcrService service(OcrApi api) {
        return new OcrService(api, new DitingProperties());
    }

    @Test
    @DisplayName("尺寸超限：直接 422，一次云端调用都不发（付费调用留给云端报错的旧行为已改掉）")
    void oversizedImageRejectedBeforeCloudCall() {
        AtomicInteger calls = new AtomicInteger();
        OcrService service = service(image -> {
            calls.incrementAndGet();
            return StubOcrApi.STUB_DATA;
        });

        assertThatThrownBy(() -> service.recognize(png(9000, 9000)))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("尺寸超出识别范围")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("未配置优先于尺寸校验：没凭据时整任务静默等待，不把图片判死")
    void notConfiguredWinsOverSizeCheck() {
        OcrService service = service(new NotConfiguredOcrApi());

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.recognize(png(9000, 9000)))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("尺寸合规：正常调用云端并把官方 Data 解析成行")
    void normalImageGoesToCloud() {
        AtomicInteger calls = new AtomicInteger();
        OcrService service = service(image -> {
            calls.incrementAndGet();
            return StubOcrApi.STUB_DATA;
        });

        OcrParser.ParsedOcr parsed = service.recognize(png(1200, 800));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(parsed.lines()).containsExactly("我保证能减免利息。", "请你今天把款还上。");
    }

    @Test
    @DisplayName("探测不出尺寸（前缀截断的 TIFF 等）：不拦，交给云端判")
    void unprobeableImageIsNotBlocked() {
        AtomicInteger calls = new AtomicInteger();
        OcrService service = service(image -> {
            calls.incrementAndGet();
            return StubOcrApi.STUB_DATA;
        });
        // TIFF 头但 IFD 被截断：probe 返回 null，不能因此拒绝
        byte[] tiffHead = {'I', 'I', 42, 0, 8, 0, 0, 0, 2, 0, 0, 1};

        assertThat(service.recognize(tiffHead).lines()).hasSize(2);
        assertThat(calls.get()).isEqualTo(1);
    }
}
