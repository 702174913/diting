package com.diting.service.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RULE-06/07：规范化与原文位置映射 */
class TextNormalizerTest {

    @Test
    @DisplayName("RULE-06 全角/半角、大小写统一，位置能回到原文")
    void normalizesWidthAndCase() {
        String original = "你好 ＷＯＲＬＤ ，收到。";
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(original);

        assertThat(normalized.text()).isEqualTo("你好 world ,收到.");
        // “world” 在规范化文本中的位置 → 原文位置应指向全角 Ｗ
        int idx = normalized.text().indexOf("world");
        int[] range = normalized.toOriginalRange(idx, idx + 5);
        assertThat(original.substring(range[0], range[1])).isEqualTo("ＷＯＲＬＤ");
    }

    @Test
    @DisplayName("RULE-06 空白折叠：多个空格/制表/全角空格折叠为一个，映射指向第一段空白起点")
    void collapsesWhitespace() {
        String original = "催收\u3000\u3000 员 说";
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(original);

        assertThat(normalized.text()).isEqualTo("催收 员 说");
        int[] range = normalized.toOriginalRange(normalized.text().indexOf("员"),
            normalized.text().indexOf("员") + 1);
        assertThat(original.substring(range[0], range[1])).isEqualTo("员");
    }

    @Test
    @DisplayName("RULE-07 emoji 代理对：区间不会切进半个字符")
    void emojiSurrogatePairs() {
        String original = "你好😀，请配合";
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(original);

        int start = normalized.text().indexOf("请");
        int[] range = normalized.toOriginalRange(start, start + 1);
        String hit = original.substring(range[0], range[1]);
        assertThat(hit).isEqualTo("请");

        // emoji 自身占两个 UTF-16 单元：跨过它的区间仍能正确落回原文
        int emojiStart = normalized.text().indexOf("😀");
        int[] emojiRange = normalized.toOriginalRange(emojiStart, emojiStart + 2);
        assertThat(original.substring(emojiRange[0], emojiRange[1])).isEqualTo("😀");
    }

    @Test
    @DisplayName("中英文混排与标点：中文标点参与规范化但不改变原文")
    void mixedLanguage() {
        String original = "Hello，你好！";
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(original);

        assertThat(normalized.text()).isEqualTo("hello,你好!");
        assertThat(original).isEqualTo("Hello，你好！");
    }

    @Test
    @DisplayName("空串与 null 安全")
    void emptyInput() {
        assertThat(TextNormalizer.normalize(null).text()).isEmpty();
        assertThat(TextNormalizer.normalize("").text()).isEmpty();
        assertThat(TextNormalizer.normalize("   ").text()).isEmpty();
    }
}
