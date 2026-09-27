package com.diting.service.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 图片文件头嗅探（S16）：只看文件头，不信任扩展名与客户端声明。
 *
 * 识别范围与阿里云 OCR 的受理范围对齐（PNG/JPG/JPEG/BMP/GIF/TIFF/WebP，官方文档核验日期 2026-09-24）；
 * 认出但不在产品允许列表里的格式由上层按「不支持」拒绝。HEIC 不在 OCR 支持范围，
 * 这里也不识别——上层会提示用户改用 JPG/PNG。
 */
public final class ImageFormatSniffer {

    /** 扩展名归一：jpeg→jpg、tif→tiff，避免「同一种格式两种写法」误判不一致 */
    private static final Map<String, String> ALIASES = Map.of("jpeg", "jpg", "tif", "tiff");

    private ImageFormatSniffer() {
    }

    public static String normalize(String format) {
        if (format == null) {
            return null;
        }
        String value = format.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(value, value);
    }

    /**
     * @return 识别出的格式小写名（jpg/png/gif/bmp/tiff/webp）；无法识别返回 null
     */
    public static String sniff(byte[] head) {
        if (head == null || head.length < 4) {
            return null;
        }
        // JPEG：FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG：89 50 4E 47 0D 0A 1A 0A
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return "png";
        }
        if (startsWith(head, "GIF87a") || startsWith(head, "GIF89a")) {
            return "gif";
        }
        if (startsWith(head, "BM")) {
            return "bmp";
        }
        // TIFF：II*\0（小端）或 MM\0*（大端）
        if ((head[0] == 'I' && head[1] == 'I' && head[2] == 0x2A && head[3] == 0x00)
            || (head[0] == 'M' && head[1] == 'M' && head[2] == 0x00 && head[3] == 0x2A)) {
            return "tiff";
        }
        // WebP：RIFF....WEBP（与 WAV 的 RIFF....WAVE 区分）
        if (head.length >= 12 && startsWith(head, "RIFF")
            && "WEBP".equals(new String(head, 8, 4, StandardCharsets.US_ASCII))) {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] head, String ascii) {
        byte[] magic = ascii.getBytes(StandardCharsets.US_ASCII);
        if (head.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
