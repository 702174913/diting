package com.diting.service.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片文件头嗅探（S16）：只认文件头，不认扩展名。
 * 范围与阿里云 OCR 受理格式对齐（PNG/JPG/JPEG/BMP/GIF/TIFF/WebP）；
 * HEIC 不在受理范围，必须认不出（上层据此提示用户换图）。
 */
class ImageFormatSnifferTest {

    private static byte[] of(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    @Test
    @DisplayName("识别常见格式：JPG/PNG/GIF/BMP/TIFF/WebP")
    void detectsCommonFormats() {
        assertThat(ImageFormatSniffer.sniff(jpeg())).isEqualTo("jpg");
        assertThat(ImageFormatSniffer.sniff(png())).isEqualTo("png");
        assertThat(ImageFormatSniffer.sniff(of("GIF89a...."))).isEqualTo("gif");
        assertThat(ImageFormatSniffer.sniff(of("BM......"))).isEqualTo("bmp");
        assertThat(ImageFormatSniffer.sniff(of("II*\u0000...."))).isEqualTo("tiff");
        assertThat(ImageFormatSniffer.sniff(of("MM\u0000*...."))).isEqualTo("tiff");
        // RIFF....WEBP（与 WAV 的 RIFF....WAVE 区分）
        assertThat(ImageFormatSniffer.sniff(of("RIFF....WEBPVP8 "))).isEqualTo("webp");
    }

    @Test
    @DisplayName("WAV 不会被误判成 WebP；音频文件在这里认不出")
    void audioIsNotAnImage() {
        assertThat(ImageFormatSniffer.sniff(of("RIFF....WAVEfmt "))).isNull();
        assertThat(ImageFormatSniffer.sniff(of("#!AMR...."))).isNull();
    }

    @Test
    @DisplayName("HEIC（iPhone 默认拍照格式）认不出——必须让用户先转 JPG")
    void heicIsNotSupported() {
        // ftypheic：ISO BMFF 的 HEIC 品牌
        assertThat(ImageFormatSniffer.sniff(of("\u0000\u0000\u0000\u0018ftypheic"))).isNull();
    }

    @Test
    @DisplayName("扩展名归一：jpeg→jpg、tif→tiff，避免同一格式两种写法被判不一致")
    void normalizesAliases() {
        assertThat(ImageFormatSniffer.normalize("JPEG")).isEqualTo("jpg");
        assertThat(ImageFormatSniffer.normalize("tif")).isEqualTo("tiff");
        assertThat(ImageFormatSniffer.normalize("PNG")).isEqualTo("png");
        assertThat(ImageFormatSniffer.normalize(null)).isNull();
    }
}
