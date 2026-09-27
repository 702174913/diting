package com.diting.service.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本规范化 + 原文位置映射（开发文档 §8）。
 *
 * 目的：匹配时忽略全角/半角、大小写与空白差异，但**输出必须能回到原文的 UTF-16 下标**
 * （前端按 JS 字符串切片高亮，服务端不能拿码点下标混用）。
 *
 * 约定：
 * - 原文一字不改，只产出规范化副本与映射；
 * - 代理对（emoji 等占两个 UTF-16 单元）整体保留，映射不会切进半个字符；
 * - 连续空白折叠为一个空格，映射指向该段空白在原文的起始位置。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * @param text 规范化文本
     * @param offsets normalized 第 i 个 UTF-16 单元对应的**原文起始下标**
     * @param originalLength 原文长度（用于把规范化区间换算成原文区间）
     */
    public record Normalized(String text, int[] offsets, int originalLength) {

        /** 规范化区间 [start, end) → 原文区间 [start, end)，左闭右开 */
        public int[] toOriginalRange(int start, int end) {
            if (start < 0 || end > text.length() || start >= end) {
                return null;
            }
            int originalStart = offsets[start];
            int lastUnit = end - 1;
            int originalEnd = offsets[lastUnit] + unitLength(originalLength, offsets, lastUnit, text, end);
            return new int[]{originalStart, originalEnd};
        }

        private static int unitLength(int originalLength, int[] offsets, int index, String text, int endExclusive) {
            // 该规范化单元在原文中占的宽度：下一个单元起点 - 当前起点；末尾用原文长度兜底
            int start = offsets[index];
            int nextStart = index + 1 < offsets.length ? offsets[index + 1] : originalLength;
            int width = Math.max(1, nextStart - start);
            // 折叠空白时宽度会跨多个原文字符，取实际覆盖
            return Math.min(width, Math.max(1, originalLength - start));
        }
    }

    /** 规范化：全角→半角、大小写统一、空白折叠 */
    public static Normalized normalize(String original) {
        if (original == null || original.isEmpty()) {
            return new Normalized("", new int[0], 0);
        }
        StringBuilder out = new StringBuilder(original.length());
        List<Integer> offsets = new ArrayList<>(original.length());
        int i = 0;
        while (i < original.length()) {
            int codePoint = original.codePointAt(i);
            int units = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || isFullWidthSpace(codePoint)) {
                int start = i;
                while (i < original.length()) {
                    int next = original.codePointAt(i);
                    if (!Character.isWhitespace(next) && !isFullWidthSpace(next)) {
                        break;
                    }
                    i += Character.charCount(next);
                }
                if (!out.isEmpty()) {
                    out.append(' ');
                    offsets.add(start);
                }
                continue;
            }
            int normalized = normalizeCodePoint(codePoint);
            if (normalized >= 0) {
                if (Character.isBmpCodePoint(normalized)) {
                    out.append((char) normalized);
                    offsets.add(i);
                }
                else {
                    // 极少见：全角字符映射后仍是增补平面，按代理对写入
                    char[] chars = Character.toChars(normalized);
                    for (char c : chars) {
                        out.append(c);
                        offsets.add(i);
                    }
                }
            }
            else {
                for (int u = 0; u < units; u++) {
                    out.append(original.charAt(i + u));
                    offsets.add(i);
                }
            }
            i += units;
        }
        // 去掉尾部折叠出来的空格（不影响内部映射）
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        out.setLength(end);
        int[] map = new int[out.length()];
        for (int k = 0; k < out.length(); k++) {
            map[k] = offsets.get(k);
        }
        return new Normalized(out.toString(), map, original.length());
    }

    private static boolean isFullWidthSpace(int codePoint) {
        return codePoint == 0x3000 || codePoint == 0x00A0;
    }

    /** @return 规范化后的码点；返回 -1 表示保持原样 */
    private static int normalizeCodePoint(int codePoint) {
        if (codePoint >= 0xFF01 && codePoint <= 0xFF5E) {
            // 全角 ASCII → 半角；全角大写字母还要一并统一成小写
            int halfWidth = codePoint - 0xFEE0;
            return (halfWidth >= 'A' && halfWidth <= 'Z') ? halfWidth + 32 : halfWidth;
        }
        if (codePoint == 0x3000) {
            return ' ';
        }
        // 常见全角标点
        return switch (codePoint) {
            case 0x3001 -> ',';   // 、
            case 0x3002 -> '.';   // 。
            case 0xFF0C -> ',';
            case 0xFF0E -> '.';
            case 0xFF01 -> '!';
            case 0xFF1F -> '?';
            case 0xFF1A -> ':';
            case 0xFF1B -> ';';
            case 0x2018, 0x2019, 0x201C, 0x201D -> '\'';
            case 0x2014, 0x2015, 0xFF0D -> '-';
            default -> {
                if (codePoint >= 'A' && codePoint <= 'Z') {
                    yield codePoint + 32;
                }
                yield -1;
            }
        };
    }
}
