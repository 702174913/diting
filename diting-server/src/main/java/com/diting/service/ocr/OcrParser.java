package com.diting.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * OCR 结果解析：**两种官方结构都认**，出口只有一个——「按阅读顺序排好的文字行」。
 *
 * 1. **RecognizeAllText**（当前默认，`Type=General`，契约核验日期 2026-09-27）：
 *    `Data` 是对象：`Content`（全文）+ `SubImages[].BlockInfo.BlockDetails[]{BlockId, BlockContent, BlockAngle}`。
 *    `Type=General` 时官方参数表**不支持** `OutputCoordinate`/`AdvancedConfig`，即没有坐标、没有
 *    `RowInfo`，所以阅读顺序不猜坐标，只用官方给的两样东西：`Content` 里的换行，与文字块编号顺序。
 *    切行口径：`Content` 有换行就按换行切；没有换行（整段一坨）时退回按文字块切——块粒度更细，
 *    规则判读更准，且不会把一句话从中间切开。
 * 2. **RecognizeGeneral**（旧接口，兼容开关 `diting.aliyun.ocr.api=general`）：
 *    `Data` 是字符串，内含 `content` + `prism_wordsInfo[]`（词块 + 坐标），按坐标先 y 后 x 归并行。
 *
 * 字段名**大小写不敏感**：官方 HTTP 报文是 PascalCase（`Content`/`BlockContent`），
 * 而 SDK 类型化模型序列化出来是 camelCase（`content`/`blockContent`），两条来源都要能解析。
 *
 * 不猜字段：两套结构都不匹配、或结构明显非法，一律抛 {@link OcrParseException}。
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
        JsonNode words = find(root, "prism_wordsinfo");
        if (words != null) {
            return parseGeneral(root, words);
        }
        JsonNode subImages = find(root, "subimages");
        JsonNode content = find(root, "content");
        if (subImages != null || content != null) {
            return parseAllText(content, subImages);
        }
        throw new OcrParseException("无法识别的 OCR 返回结构："
            + "既没有 SubImages/Content（RecognizeAllText），也没有 prism_wordsInfo（RecognizeGeneral）");
    }

    /** 旧接口：取每个词的左上角坐标，按阅读顺序归并行 */
    private static ParsedOcr parseGeneral(JsonNode root, JsonNode words) {
        String content = root.path("content").asText("");
        if (!words.isArray()) {
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

    /** 统一识别：行来自 Content 的换行，整段时退回文字块 */
    private static ParsedOcr parseAllText(JsonNode contentNode, JsonNode subImages) {
        String content = contentNode == null || contentNode.isNull() ? "" : contentNode.asText("");
        List<String> lines = splitLines(content);
        List<String> blocks = blockLines(subImages);
        if (blocks.size() > lines.size()) {
            lines = blocks;
        }
        return new ParsedOcr(lines, content);
    }

    /** 按换行切行（Content 是官方给的全文，换行反映原始版面） */
    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\r\\n|\\r|\\n")) {
            String line = normalizeLine(raw);
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 文字块顺序：子图按 SubImageId、块按 BlockId（官方编号即阅读顺序，General 没有坐标可用） */
    private static List<String> blockLines(JsonNode subImages) {
        if (subImages == null || !subImages.isArray()) {
            return List.of();
        }
        List<JsonNode> subs = new ArrayList<>();
        subImages.forEach(subs::add);
        subs.sort(Comparator.comparingInt(sub -> intField(sub, "subimageid")));
        List<Indexed> blocks = new ArrayList<>();
        for (JsonNode sub : subs) {
            JsonNode details = find(find(sub, "blockinfo"), "blockdetails");
            if (details == null || !details.isArray()) {
                continue;
            }
            for (JsonNode detail : details) {
                JsonNode textNode = find(detail, "blockcontent");
                String text = textNode == null ? "" : normalizeLine(textNode.asText(""));
                if (text.isEmpty()) {
                    continue;
                }
                blocks.add(new Indexed(intField(detail, "blockid"), text));
            }
        }
        blocks.sort(Comparator.comparingInt(Indexed::id));
        return blocks.stream().map(Indexed::text).toList();
    }

    /**
     * 行归一化：空白折叠成单空格、去首尾空白；**两个非 ASCII 字符之间的空格去掉**——
     * 中文不该被排版空格切开（规则关键词按中文短语匹配），口径与旧接口的词块拼接一致。
     */
    static String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        StringBuilder out = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            if (c == ' ' && out.length() > 0 && isWide(out.charAt(out.length() - 1))
                && i + 1 < collapsed.length() && isWide(collapsed.charAt(i + 1))) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** 非 ASCII 且非空白：中文/日文/全角标点等（ASCII 空格只用来分隔英文单词） */
    private static boolean isWide(char c) {
        return c > 127 && !Character.isWhitespace(c);
    }

    /** 按行归并（旧接口）：同一行的高度容差取该行文字块高度的一半（至少 8px） */
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

    /** 大小写不敏感的字段查找（官方报文 PascalCase、SDK 模型 camelCase 都要认） */
    private static JsonNode find(JsonNode node, String lowerName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.toLowerCase(Locale.ROOT).equals(lowerName)) {
                return node.get(name);
            }
        }
        return null;
    }

    /** 编号字段：缺失时给 Integer.MAX_VALUE，稳定排序下保持官方给的原始顺序 */
    private static int intField(JsonNode node, String lowerName) {
        JsonNode value = find(node, lowerName);
        return value == null || !value.canConvertToInt() ? Integer.MAX_VALUE : value.asInt(Integer.MAX_VALUE);
    }

    private record Indexed(int id, String text) {
    }
}
