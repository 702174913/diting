package com.diting.service.storage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 音频容器格式嗅探：只看文件头，不信任扩展名与客户端声明。
 *
 * 支持范围与产品允许列表（diting.media.allowed-formats）配合使用；
 * 认出但不在允许列表里的格式（例如 mp4 视频容器）会被上层按“不支持”拒绝。
 */
public final class AudioFormatSniffer {

    private AudioFormatSniffer() {
    }

    /**
     * @return 识别出的格式小写名（m4a/mp4/mp3/wav/flac/amr/aac/ogg）；无法识别返回 null
     */
    public static String sniff(byte[] head) {
        if (head == null || head.length < 4) {
            return null;
        }
        if (startsWith(head, "#!AMR")) {
            return "amr";
        }
        if (startsWith(head, "fLaC")) {
            return "flac";
        }
        if (startsWith(head, "OggS")) {
            return "ogg";
        }
        if (startsWith(head, "RIFF") && head.length >= 12
            && "WAVE".equals(new String(head, 8, 4, StandardCharsets.US_ASCII))) {
            return "wav";
        }
        if (startsWith(head, "ID3")) {
            return "mp3";
        }
        // ISO BMFF：偏移 4 起是 "ftyp"，随后是 major brand
        if (head.length >= 12 && "ftyp".equals(new String(head, 4, 4, StandardCharsets.US_ASCII))) {
            String brand = new String(head, 8, 4, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
            return brand.startsWith("M4A") || brand.startsWith("M4B") ? "m4a" : "mp4";
        }
        // MP3 帧同步：0xFF 0xE0 掩码；AAC ADTS：0xFF 0xF0 掩码
        if ((head[0] & 0xFF) == 0xFF) {
            int second = head[1] & 0xFF;
            if ((second & 0xE0) == 0xE0) {
                return (second & 0xF6) == 0xF0 ? "aac" : "mp3";
            }
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
