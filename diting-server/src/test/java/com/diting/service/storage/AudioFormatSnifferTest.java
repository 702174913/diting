package com.diting.service.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 文件头嗅探：不看扩展名，只认字节（UPLOAD-02 的判定依据） */
class AudioFormatSnifferTest {

    private static byte[] bytes(int length, String head) {
        byte[] out = new byte[length];
        byte[] prefix = head.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, out, 0, Math.min(prefix.length, length));
        return out;
    }

    private static byte[] m4a() {
        byte[] head = bytes(32, "0000ftypM4A ");
        head[0] = 0;
        head[1] = 0;
        head[2] = 0;
        head[3] = 32;
        return head;
    }

    @Test
    @DisplayName("ISO BMFF：M4A 品牌识别为 m4a，其它品牌识别为 mp4")
    void isoBmff() {
        assertThat(AudioFormatSniffer.sniff(m4a())).isEqualTo("m4a");
        assertThat(AudioFormatSniffer.sniff(bytes(32, "0000ftypisom"))).isEqualTo("mp4");
    }

    @Test
    @DisplayName("常见音频容器识别")
    void knownContainers() {
        assertThat(AudioFormatSniffer.sniff(bytes(16, "ID3"))).isEqualTo("mp3");
        assertThat(AudioFormatSniffer.sniff(bytes(16, "RIFF0000WAVE"))).isEqualTo("wav");
        assertThat(AudioFormatSniffer.sniff(bytes(16, "fLaC"))).isEqualTo("flac");
        assertThat(AudioFormatSniffer.sniff(bytes(16, "#!AMR"))).isEqualTo("amr");
        assertThat(AudioFormatSniffer.sniff(bytes(16, "OggS"))).isEqualTo("ogg");
    }

    @Test
    @DisplayName("MP3 帧同步与 AAC ADTS 按位区分")
    void frameSync() {
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
        byte[] aac = {(byte) 0xFF, (byte) 0xF1, 0x50, 0x00};
        assertThat(AudioFormatSniffer.sniff(mp3)).isEqualTo("mp3");
        assertThat(AudioFormatSniffer.sniff(aac)).isEqualTo("aac");
    }

    @Test
    @DisplayName("无法识别与过短输入返回 null，交由上层拒绝")
    void unknown() {
        assertThat(AudioFormatSniffer.sniff(bytes(16, "PK"))).isNull();
        assertThat(AudioFormatSniffer.sniff(new byte[]{1, 2})).isNull();
        assertThat(AudioFormatSniffer.sniff(null)).isNull();
        assertThat(AudioFormatSniffer.sniff(new byte[0])).isNull();
    }
}
