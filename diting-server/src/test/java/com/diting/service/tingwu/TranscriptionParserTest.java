package com.diting.service.tingwu;

import com.diting.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ASR-04/05/06/07/09/10：转写结果解析。
 * 样本取自 src/test/resources/tingwu/transcription-sample.json（脱敏契约样本）。
 */
class TranscriptionParserTest {

    private final TranscriptionParser parser = new TranscriptionParser();

    private static String fixture(String name) {
        try (InputStream in = TranscriptionParserTest.class.getResourceAsStream("/tingwu/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            throw new IllegalStateException("读取样本失败：" + name, e);
        }
    }

    @Test
    @DisplayName("ASR-05 时间戳乱序时按时间重排，sequence 连续，起止保留毫秒")
    void sortsByTime() {
        TranscriptionParser.ParsedTranscription parsed = parser.parse(fixture("transcription-sample.json"));

        assertThat(parsed.sentences()).hasSize(3);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::sequence)
            .containsExactly(1, 2, 3);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::startMs)
            .containsExactly(0L, 900L, 2000L);
        assertThat(parsed.sentences().get(0).endMs()).isEqualTo(900L);
        // 覆盖时长按最晚结束时间推算
        assertThat(parsed.durationMs()).isEqualTo(3400L);
    }

    @Test
    @DisplayName("同一 Paragraph 内按 SentenceId 逐句拆分，继承听悟 SpeakerId")
    void splitsSentencesInsideParagraph() {
        var parsed = parser.parse("""
            {"Transcription":{"Paragraphs":[
              {"ParagraphId":"p-7","SpeakerId":"7","Words":[
                {"SentenceId":1,"Text":"第一句。","Start":0,"End":400},
                {"SentenceId":2,"Text":"第二句。","Start":400,"End":800}]},
              {"ParagraphId":"p-9","SpeakerId":"9","Words":[
                {"SentenceId":3,"Text":"另一段。","Start":800,"End":1100}]}
            ]}}
            """);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::text)
            .containsExactly("第一句。", "第二句。", "另一段。");
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::speakerId)
            .containsExactly(7, 7, 9);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::startMs)
            .containsExactly(0L, 400L, 800L);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::sequence)
            .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("1044 型结果：一个 Paragraph、六个 SentenceId 产出六句，但只有一个听悟说话人")
    void sixSentencesOneParagraphOneSpeaker() {
        var parsed = parser.parse("""
            {"Transcription":{"Paragraphs":[{"ParagraphId":0,"SpeakerId":"1","Words":[
              {"SentenceId":1,"Text":"甲。","Start":0,"End":100},
              {"SentenceId":2,"Text":"乙。","Start":100,"End":200},
              {"SentenceId":3,"Text":"丙。","Start":200,"End":300},
              {"SentenceId":4,"Text":"丁。","Start":300,"End":400},
              {"SentenceId":5,"Text":"戊。","Start":400,"End":500},
              {"SentenceId":6,"Text":"己。","Start":500,"End":600}
            ]}]}}
            """);
        assertThat(parsed.sentences()).hasSize(6);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::text)
            .containsExactly("甲。", "乙。", "丙。", "丁。", "戊。", "己。");
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::speakerId)
            .containsExactly(1, 1, 1, 1, 1, 1);
        assertThat(parsed.speakerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 SentenceId 时整段保留；局部缺失不额外切句，也不跨 Paragraph 合并")
    void missingSentenceIdsDoNotInventSegments() {
        var parsed = parser.parse("""
            {"Transcription":{"Paragraphs":[
              {"SpeakerId":"3","Words":[
                {"Text":"第一","Start":0,"End":100},
                {"SentenceId":1,"Text":"句。","Start":100,"End":200},
                {"Text":"接着","Start":200,"End":300},
                {"SentenceId":2,"Text":"第二句。","Start":300,"End":400}]},
              {"SpeakerId":"4","Words":[
                {"SentenceId":2,"Text":"另一位。","Start":400,"End":500}]}
            ]}}
            """);
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::text)
            .containsExactly("第一句。接着", "第二句。", "另一位。");
        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::speakerId)
            .containsExactly(3, 3, 4);
    }

    @Test
    @DisplayName("ASR-06 连续同 speaker 不合并、多人各自保留 speakerId")
    void keepsSpeakerGranularity() {
        TranscriptionParser.ParsedTranscription parsed = parser.parse(fixture("transcription-sample.json"));

        assertThat(parsed.sentences()).extracting(TranscriptionParser.ParsedSentence::speakerId)
            .containsExactly(1, 2, 1);
        assertThat(parsed.speakerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("ASR-10 标点与原文逐字保留（含无标点句）")
    void preservesOriginalText() {
        TranscriptionParser.ParsedTranscription parsed = parser.parse(fixture("transcription-sample.json"));

        assertThat(parsed.sentences().get(0).text()).isEqualTo("你好，我");
        assertThat(parsed.sentences().get(1).text()).isEqualTo("喂，你好。");
        assertThat(parsed.sentences().get(1).words().get(0).word()).isEqualTo("喂，");
        assertThat(parsed.sentences().get(2).text()).isEqualTo("这句话没有标点");
        // 词级明细保留，供 S07 做 UTF-16 偏移映射
        assertThat(parsed.sentences().get(2).words()).hasSize(3);
        assertThat(parsed.sentences().get(2).words().get(0).word()).isEqualTo("这句话");
    }

    @Test
    @DisplayName("ASR-07 未知说话人保留为空而不是 0；缺时间戳明确报错")
    void unknownSpeakerAndMissingTimestamps() {
        TranscriptionParser.ParsedTranscription parsed = parser.parse("""
            {"Transcription":{"Paragraphs":[{"Words":[{"Text":"嗯","Start":0,"End":100}]}]}}
            """);
        assertThat(parsed.sentences().get(0).speakerId()).isNull();

        assertThatThrownBy(() -> parser.parse("""
            {"Transcription":{"Paragraphs":[{"SpeakerId":"1","Words":[{"Text":"嗯"}]}]}}
            """))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("缺少毫秒级 Start");

        assertThatThrownBy(() -> parser.parse("""
            {"Transcription":{"Paragraphs":[{"SpeakerId":"x","Words":[{"Text":"嗯","Start":0,"End":1}]}]}}
            """))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("SpeakerId 不是数字");

        assertThatThrownBy(() -> parser.parse("""
            {"Transcription":{"Paragraphs":[{"Words":[{"Text":"嗯","Start":500,"End":100}]}]}}
            """))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("时间戳倒挂");

        assertThatThrownBy(() -> parser.parse("""
            {"Transcription":{"Paragraphs":[{"Words":[{"SentenceId":{},"Text":"嗯","Start":0,"End":100}]}]}}
            """))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("SentenceId 不是数字或字符串");
    }

    @Test
    @DisplayName("ASR-02 缺 Transcription / Paragraphs / Words：明确报错，不猜候选键")
    void strictAboutStructure() {
        assertThatThrownBy(() -> parser.parse("{\"Sentences\":[]}"))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("缺少 Transcription 节点");
        assertThatThrownBy(() -> parser.parse("{\"Transcription\":{}}"))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("Paragraphs");
        assertThatThrownBy(() -> parser.parse("{\"Transcription\":{\"Paragraphs\":[{}]}}"))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("Words");
        assertThatThrownBy(() -> parser.parse(""))
            .isInstanceOf(TranscriptionParseException.class);
        assertThatThrownBy(() -> parser.parse("""
            {"Transcription":{"Paragraphs":[{"Words":[{"Word":"旧字段","Start":0,"End":1}]}]}}
            """))
            .isInstanceOf(TranscriptionParseException.class)
            .hasMessageContaining("缺少 Text");
    }

    @Test
    @DisplayName("ASR-04 空音频：Paragraphs 为空返回空结果，由调用方判定 INVALID")
    void emptyAudio() {
        TranscriptionParser.ParsedTranscription parsed = parser.parse("{\"Transcription\":{\"Paragraphs\":[]}}");

        assertThat(parsed.isEmpty()).isTrue();
        assertThat(parsed.speakerCount()).isZero();
        assertThat(parsed.durationMs()).isZero();
    }

    @Test
    @DisplayName("ASR-09 超大结果：超过词数上限明确报错")
    void tooLarge() {
        TranscriptionParser small = new TranscriptionParser(3);
        String json = """
            {"Transcription":{"Paragraphs":[
              {"SpeakerId":"1","Words":[
                {"Text":"a","Start":0,"End":1},
                {"Text":"b","Start":1,"End":2},
                {"Text":"c","Start":2,"End":3},
                {"Text":"d","Start":3,"End":4}]}]}}
            """;

        assertThatThrownBy(() -> small.parse(json))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("结果过大");
    }
}
