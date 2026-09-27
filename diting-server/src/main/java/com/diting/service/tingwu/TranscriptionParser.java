package com.diting.service.tingwu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 转写结果解析（开发文档 §5、§8）。
 *
 * 规则（对应 S05 的顺序要求）：
 * 1. 每个 Paragraph 内按 Words[i].SentenceId 的连续变化拆句；缺失时保留在相邻句中，不凭标点臆造句子；
 * 2. 每句继承所属 Paragraph 的 SpeakerId，保留毫秒起止与原文（Words[i].Text 已包含标点）；
 * 3. 未知字段不猜：缺 Transcription / Paragraphs / Words / Text / Start / End 一律明确报错；
 * 4. 空音频（Paragraphs 为空）返回空结果，由调用方判定为 INVALID，不当作解析失败。
 */
public class TranscriptionParser {

    /** 官方契约的入口路径，不在此路径下的候选键一律不认 */
    static final String TRANSCRIPTION_NODE = "Transcription";
    static final String PARAGRAPHS_NODE = "Paragraphs";
    static final String WORDS_NODE = "Words";

    /** 结果体积上限：超过视为异常（防止超大结果拖垮进程），ASR-09 */
    private static final int DEFAULT_MAX_WORDS = 200_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxWords;

    public TranscriptionParser() {
        this(DEFAULT_MAX_WORDS);
    }

    public TranscriptionParser(int maxWords) {
        this.maxWords = maxWords;
    }

    public ParsedTranscription parse(String transcriptionJson) {
        if (transcriptionJson == null || transcriptionJson.isBlank()) {
            throw new TranscriptionParseException("转写结果为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(transcriptionJson);
        }
        catch (Exception e) {
            throw new TranscriptionParseException("转写结果不是合法 JSON：" + e.getMessage());
        }
        JsonNode transcription = root.get(TRANSCRIPTION_NODE);
        if (transcription == null || transcription.isNull()) {
            throw new TranscriptionParseException("转写结果缺少 " + TRANSCRIPTION_NODE + " 节点");
        }
        JsonNode paragraphs = transcription.get(PARAGRAPHS_NODE);
        if (paragraphs == null || !paragraphs.isArray()) {
            throw new TranscriptionParseException(
                "转写结果缺少 " + TRANSCRIPTION_NODE + "." + PARAGRAPHS_NODE + " 数组");
        }

        List<RawSegment> segments = new ArrayList<>();
        int totalWords = 0;
        for (int p = 0; p < paragraphs.size(); p++) {
            JsonNode paragraph = paragraphs.get(p);
            JsonNode words = paragraph.get(WORDS_NODE);
            if (words == null || !words.isArray()) {
                throw new TranscriptionParseException(
                    "第 " + p + " 段缺少 " + WORDS_NODE + " 数组");
            }
            totalWords += words.size();
            if (totalWords > maxWords) {
                throw new TranscriptionParseException("转写结果过大（词数超过 " + maxWords + "）");
            }
            Integer speakerId = readSpeakerId(paragraph, p);
            List<ParsedWord> sentenceWords = new ArrayList<>();
            String currentSentenceId = null;
            for (int w = 0; w < words.size(); w++) {
                JsonNode word = words.get(w);
                String token = textOf(word, "Text", p, w);
                long wordStart = millis(word, "Start", p, w);
                long wordEnd = millis(word, "End", p, w);
                if (wordEnd < wordStart) {
                    throw new TranscriptionParseException(
                        "第 " + p + " 段第 " + w + " 个词的时间戳倒挂（Start " + wordStart + " > End " + wordEnd + "）");
                }
                String sentenceId = sentenceIdOf(word, p, w);
                if (sentenceId != null) {
                    if (currentSentenceId != null && !currentSentenceId.equals(sentenceId)) {
                        addSegment(segments, speakerId, sentenceWords);
                        sentenceWords = new ArrayList<>();
                    }
                    currentSentenceId = sentenceId;
                }
                // 听悟的 Text 自带标点；不要再拼接不存在的 Punctuation 字段。
                sentenceWords.add(new ParsedWord(token, wordStart, wordEnd, ""));
            }
            // 空段不伪造时间戳；缺 SentenceId 的整段仍只显示一条。
            addSegment(segments, speakerId, sentenceWords);
        }

        // 只按时间排序（同一时间戳保持原始段落顺序），再统一编号
        segments.sort(Comparator.comparingLong(RawSegment::startMs).thenComparingInt(RawSegment::index));
        List<ParsedSentence> sentences = new ArrayList<>(segments.size());
        Set<Integer> speakers = new LinkedHashSet<>();
        long duration = 0;
        for (int i = 0; i < segments.size(); i++) {
            RawSegment segment = segments.get(i);
            if (segment.speakerId() != null) {
                speakers.add(segment.speakerId());
            }
            duration = Math.max(duration, segment.endMs());
            sentences.add(new ParsedSentence(i + 1, segment.speakerId(), segment.startMs(),
                segment.endMs(), segment.text(), segment.words()));
        }
        return new ParsedTranscription(List.copyOf(sentences), duration, speakers.size());
    }

    private static void addSegment(List<RawSegment> segments, Integer speakerId, List<ParsedWord> words) {
        if (words.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (ParsedWord word : words) {
            text.append(word.word());
            start = Math.min(start, word.startMs());
            end = Math.max(end, word.endMs());
        }
        segments.add(new RawSegment(segments.size(), speakerId, start, end, text.toString(), List.copyOf(words)));
    }

    private static String sentenceIdOf(JsonNode word, int paragraph, int index) {
        JsonNode value = word.get("SentenceId");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() && !value.isTextual()) {
            throw new TranscriptionParseException(
                "第 " + paragraph + " 段第 " + index + " 个词的 SentenceId 不是数字或字符串");
        }
        String id = value.asText().trim();
        return id.isEmpty() ? null : id;
    }

    private static Integer readSpeakerId(JsonNode paragraph, int index) {
        JsonNode speaker = paragraph.get("SpeakerId");
        if (speaker == null || speaker.isNull()) {
            // 未知说话人保留为空，不写 0（0 是合法聚类标签，不能当“未知”）
            return null;
        }
        if (speaker.isNumber()) {
            return speaker.asInt();
        }
        String value = speaker.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        }
        catch (NumberFormatException e) {
            throw new TranscriptionParseException(
                "第 " + index + " 段的 SpeakerId 不是数字：" + value);
        }
    }

    private static String textOf(JsonNode node, String field, int paragraph, int word) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new TranscriptionParseException(
                "第 " + paragraph + " 段第 " + word + " 个词缺少 " + field);
        }
        return value.asText();
    }

    private static long millis(JsonNode node, String field, int paragraph, int word) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new TranscriptionParseException(
                "第 " + paragraph + " 段第 " + word + " 个词缺少毫秒级 " + field);
        }
        return value.asLong();
    }

    private record RawSegment(int index, Integer speakerId, long startMs, long endMs, String text,
                              List<ParsedWord> words) {
    }

    /** 词级明细：保留原始词与标点，便于 S07 做 UTF-16 偏移映射 */
    public record ParsedWord(String word, long startMs, long endMs, String punctuation) {
    }

    public record ParsedSentence(int sequence, Integer speakerId, long startMs, long endMs, String text,
                                 List<ParsedWord> words) {
    }

    public record ParsedTranscription(List<ParsedSentence> sentences, long durationMs, int speakerCount) {

        public boolean isEmpty() {
            return sentences.isEmpty();
        }
    }
}
