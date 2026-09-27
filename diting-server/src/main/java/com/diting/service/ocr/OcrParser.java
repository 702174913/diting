package com.diting.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OCR 结果解析（官方契约核验日期：2026-09-24）。
 *
 * 官方 `Data` 是**字符串**，内容是 JSON：
 * `{"content":"全文","prism_wordsInfo":[{"word":"...","pos":[{"x":..,"y":..}],"height":..}],"width":..,"height":..}`
 * 这里只取 `word` 与左上角坐标，按**阅读顺序**（先上后下、同高从左到右）拼成行；
 * 每行作为一条「句子」交给规则引擎——这样「命中区间」在报告里能精确到某一行文字。
 *
 * 不猜字段：缺 `prism_wordsInfo` 或坐标非法一律抛 {@link OcrParseException}。
 */
public final class OcrParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OcrParser() {
    }

    /** 文字块（一个词或一小段） */
    public record Block(String text, double x, double y, double height) {
    }

    /** 解析结果：按阅读顺序排好的行 + 官方给的全文 */
    public record ParsedOcr(List<String> lines, String content) {
    }

    public static ParsedOcr parse(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            throw new OcrParseException("OCR 返回为空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(dataJson);
        }
        catch (Exception e) {
            throw new OcrParseException("OCR 返回不是合法 JSON", e);
        }
        if (!root.isObject()) {
            throw new OcrParseException("OCR 返回不是对象结构");
        }
        String content = root.path("content").asText("");
        JsonNode words = root.get("prism_wordsInfo");
        if (words == null || !words.isArray()) {
            throw new OcrParseException("OCR 返回缺少 prism_wordsInfo 数组");
        }
        List<Block> blocks = new ArrayList<>();
        for (JsonNode node : words) {
            String word = node.path("word").asText("");
            if (word.isBlank()) {
                continue;
            }
            JsonNode first = node.path("pos").path(0);
            double x = first.path("x").asDouble(Double.NaN);
            double y = first.path("y").asDouble(Double.NaN);
            double height = node.path("height").asDouble(0);
            if (Double.isNaN(x) || Double.isNaN(y)) {
                throw new OcrParseException("OCR 文字块缺少坐标：" + word);
            }
            blocks.add(new Block(word, x, y, height));
        }
        if (blocks.isEmpty()) {
            // 图上没有文字：返回空行集合（上层按 EMPTY 处理），不是解析错误
            return new ParsedOcr(List.of(), content);
        }
        return new ParsedOcr(groupLines(blocks), content);
    }

    /** 按行归并：同一行的高度容差取该行文字块高度的一半（至少 8px） */
    private static List<String> groupLines(List<Block> blocks) {
        List<Block> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingDouble(Block::y).thenComparingDouble(Block::x));
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        double lineY = Double.NaN;
        double tolerance = 8;
        for (Block block : sorted) {
            if (Double.isNaN(lineY)) {
                lineY = block.y();
                tolerance = Math.max(8, block.height() / 2);
            }
            else if (block.y() - lineY > tolerance) {
                lines.add(joinWords(current));
                current.setLength(0);
                lineY = block.y();
                tolerance = Math.max(8, block.height() / 2);
            }
            else {
                tolerance = Math.max(tolerance, Math.max(8, block.height() / 2));
            }
            appendWord(current, block.text());
        }
        if (current.length() > 0) {
            lines.add(joinWords(current));
        }
        return lines;
    }

    /**
     * 拼接同一行的词：中文直接连写；两侧都是 ASCII 字母/数字时补一个空格，
     * 避免把 "iPhone" "12" 粘成 "iPhone12"。
     */
    private static void appendWord(StringBuilder buffer, String word) {
        if (buffer.length() > 0 && needsSpace(buffer.charAt(buffer.length() - 1), word.charAt(0))) {
            buffer.append(' ');
        }
        buffer.append(word);
    }

    private static boolean needsSpace(char left, char right) {
        return (Character.isLetterOrDigit(left) && left < 128)
            && (Character.isLetterOrDigit(right) && right < 128);
    }

    private static String joinWords(StringBuilder buffer) {
        return buffer.toString().trim();
    }
}
