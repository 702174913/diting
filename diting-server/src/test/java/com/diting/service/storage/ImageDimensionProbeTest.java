package com.diting.service.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片尺寸探测与官方受理范围校验（S16 补强，核验日期 2026-09-27）。
 *
 * 覆盖：PNG/GIF/BMP/JPEG/WebP(VP8X|VP8 |VP8L)/TIFF 的尺寸读取、探测不出来必须返回 null（不拦）、
 * 以及 15–8192 像素与长宽比 <50 三条官方硬约束。
 */
class ImageDimensionProbeTest {

    private static final int MIN = 15;
    private static final int MAX = 8192;
    private static final int RATIO = 50;

    private static void ascii(byte[] b, int at, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, b, at, bytes.length);
    }

    private static void be(byte[] b, int at, int width, int value) {
        for (int i = 0; i < width; i++) {
            b[at + i] = (byte) (value >>> (8 * (width - 1 - i)));
        }
    }

    private static void le(byte[] b, int at, int width, int value) {
        for (int i = 0; i < width; i++) {
            b[at + i] = (byte) (value >>> (8 * i));
        }
    }

    private static byte[] png(int width, int height) {
        byte[] b = new byte[24];
        b[0] = (byte) 0x89;
        ascii(b, 1, "PNG");
        b[4] = 0x0D;
        b[5] = 0x0A;
        b[6] = 0x1A;
        b[7] = 0x0A;
        ascii(b, 12, "IHDR");
        be(b, 16, 4, width);
        be(b, 20, 4, height);
        return b;
    }

    private static byte[] gif(int width, int height) {
        byte[] b = new byte[10];
        ascii(b, 0, "GIF89a");
        le(b, 6, 2, width);
        le(b, 8, 2, height);
        return b;
    }

    private static byte[] bmp(int width, int height) {
        byte[] b = new byte[26];
        ascii(b, 0, "BM");
        le(b, 18, 4, width);
        le(b, 22, 4, height);
        return b;
    }

    /** SOI + APP0 段（长 16）跳过一段，再放 SOF0 */
    private static byte[] jpeg(int width, int height) {
        byte[] b = new byte[30];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        b[3] = (byte) 0xE0;
        b[4] = 0;
        b[5] = 16;
        b[20] = (byte) 0xFF;
        b[21] = (byte) 0xC0;
        b[22] = 0;
        b[23] = 17;
        b[24] = 8;
        be(b, 25, 2, height);
        be(b, 27, 2, width);
        return b;
    }

    private static byte[] webpVp8x(int width, int height) {
        byte[] b = new byte[30];
        ascii(b, 0, "RIFF");
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8X");
        le(b, 16, 4, 10);
        le(b, 24, 3, width - 1);
        le(b, 27, 3, height - 1);
        return b;
    }

    private static byte[] webpVp8(int width, int height) {
        byte[] b = new byte[30];
        ascii(b, 0, "RIFF");
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8 ");
        le(b, 16, 4, 10);
        b[23] = (byte) 0x9D;
        b[24] = 0x01;
        b[25] = 0x2A;
        le(b, 26, 2, width);
        le(b, 28, 2, height);
        return b;
    }

    private static byte[] webpVp8l(int width, int height) {
        byte[] b = new byte[25];
        ascii(b, 0, "RIFF");
        ascii(b, 8, "WEBP");
        ascii(b, 12, "VP8L");
        le(b, 16, 4, 5);
        b[20] = 0x2F;
        long bits = (long) (width - 1) | ((long) (height - 1) << 14);
        le(b, 21, 4, (int) bits);
        return b;
    }

    private static byte[] tiffLe(int width, int height) {
        byte[] b = new byte[34];
        ascii(b, 0, "II");
        b[2] = 42;
        le(b, 4, 4, 8);
        le(b, 8, 2, 2);
        // 256 ImageWidth：LONG，值内联
        le(b, 10, 2, 256);
        le(b, 12, 2, 4);
        le(b, 14, 4, 1);
        le(b, 18, 4, width);
        // 257 ImageLength：SHORT，值内联
        le(b, 22, 2, 257);
        le(b, 24, 2, 3);
        le(b, 26, 4, 1);
        le(b, 30, 2, height);
        return b;
    }

    @Test
    @DisplayName("六种受理格式都能读出像素尺寸")
    void readsDimensionsOfAllSupportedFormats() {
        assertThat(ImageDimensionProbe.probe(png(1200, 800))).isEqualTo(new ImageDimensionProbe.Size(1200, 800));
        assertThat(ImageDimensionProbe.probe(gif(640, 480))).isEqualTo(new ImageDimensionProbe.Size(640, 480));
        assertThat(ImageDimensionProbe.probe(bmp(1024, 768))).isEqualTo(new ImageDimensionProbe.Size(1024, 768));
        assertThat(ImageDimensionProbe.probe(jpeg(2000, 1500))).isEqualTo(new ImageDimensionProbe.Size(2000, 1500));
        assertThat(ImageDimensionProbe.probe(webpVp8x(1600, 900))).isEqualTo(new ImageDimensionProbe.Size(1600, 900));
        assertThat(ImageDimensionProbe.probe(webpVp8(800, 600))).isEqualTo(new ImageDimensionProbe.Size(800, 600));
        assertThat(ImageDimensionProbe.probe(webpVp8l(320, 240))).isEqualTo(new ImageDimensionProbe.Size(320, 240));
        assertThat(ImageDimensionProbe.probe(tiffLe(1500, 1000))).isEqualTo(new ImageDimensionProbe.Size(1500, 1000));
    }

    @Test
    @DisplayName("探测不出来返回 null：不拿不确定的结论拒用户的图")
    void unknownOrTruncatedHeadersYieldNull() {
        assertThat(ImageDimensionProbe.probe(null)).isNull();
        assertThat(ImageDimensionProbe.probe(new byte[0])).isNull();
        assertThat(ImageDimensionProbe.probe("不是图片".getBytes(StandardCharsets.UTF_8))).isNull();
        // 前缀被截断（只读到 PNG 签名）
        assertThat(ImageDimensionProbe.probe(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0}))
            .isNull();
        // JPEG 只有 SOI/APP0，看不到 SOFn
        byte[] jpegHead = jpeg(100, 100);
        byte[] truncated = new byte[16];
        System.arraycopy(jpegHead, 0, truncated, 0, 16);
        assertThat(ImageDimensionProbe.probe(truncated)).isNull();
        // WebP 的 VP8 块起始码不对（结构可疑）
        byte[] brokenWebp = webpVp8(100, 100);
        brokenWebp[23] = 0x00;
        assertThat(ImageDimensionProbe.probe(brokenWebp)).isNull();
        // TIFF 尺寸字段为 0（结构可疑）
        assertThat(ImageDimensionProbe.probe(tiffLe(0, 0))).isNull();
    }

    @Test
    @DisplayName("官方受理范围：长宽需大于 15 且小于 8192 像素、长宽比小于 50")
    void flagsOutOfRangeSizes() {
        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(1000, 800), MIN, MAX, RATIO)).isNull();

        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(15, 800), MIN, MAX, RATIO))
            .contains("尺寸超出识别范围");
        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(8192, 800), MIN, MAX, RATIO))
            .contains("尺寸超出识别范围");
        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(9000, 9000), MIN, MAX, RATIO))
            .contains("9000×9000");
        // 长宽比 80 > 50
        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(4000, 50), MIN, MAX, RATIO))
            .contains("长宽比");
        // 探测不出尺寸时不判定
        assertThat(ImageDimensionProbe.outOfOcrRange(null, MIN, MAX, RATIO)).isNull();
        // 比例校验可关闭（<=0）
        assertThat(ImageDimensionProbe.outOfOcrRange(new ImageDimensionProbe.Size(4000, 50), MIN, MAX, 0)).isNull();
    }
}
