package com.diting.service.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 结果解析：官方 `Data` 是 JSON 字符串，含 `content` 与 `prism_wordsInfo[]`（核验日期 2026-09-24）。
 * 这里锁住三件事：阅读顺序（先上后下、同高从左到右）、同行归并、结构异常必须明确报错。
 */
class OcrParserTest {

    @Test
    @DisplayName("按坐标排出阅读顺序，并把同一行的词合成一行")
    void ordersByReadingOrder() {
        String data = """
            {"content":"第一行第二行",
             "prism_wordsInfo":[
               {"word":"第二","pos":[{"x":120,"y":30}],"height":24},
               {"word":"行一","pos":[{"x":20,"y":30}],"height":24},
               {"word":"第三行","pos":[{"x":20,"y":80}],"height":24}],
             "height":200,"width":400}
            """;

        OcrParser.ParsedOcr parsed = OcrParser.parse(data);

        assertThat(parsed.lines()).containsExactly("行一第二", "第三行");
        assertThat(parsed.content()).isEqualTo("第一行第二行");
    }

    @Test
    @DisplayName("英文与数字之间补空格，中文直接连写")
    void joinsLatinWordsWithSpace() {
        String data = """
            {"content":"iPhone 12",
             "prism_wordsInfo":[
               {"word":"iPhone","pos":[{"x":20,"y":30}],"height":20},
               {"word":"12","pos":[{"x":90,"y":30}],"height":20},
               {"word":"购买","pos":[{"x":130,"y":30}],"height":20}],
             "height":100,"width":300}
            """;

        assertThat(OcrParser.parse(data).lines()).containsExactly("iPhone 12购买");
    }

    @Test
    @DisplayName("图上没有文字：返回空行集合，不报错（上层按 EMPTY 处理）")
    void emptyWordsMeansNoText() {
        OcrParser.ParsedOcr parsed = OcrParser.parse("{\"content\":\"\",\"prism_wordsInfo\":[]}");

        assertThat(parsed.lines()).isEmpty();
    }

    @Test
    @DisplayName("缺 prism_wordsInfo 或坐标非法：明确抛解析异常，不猜字段")
    void malformedDataFails() {
        assertThatThrownBy(() -> OcrParser.parse("{\"content\":\"x\"}"))
            .isInstanceOf(OcrParseException.class)
            .hasMessageContaining("prism_wordsInfo");

        assertThatThrownBy(() -> OcrParser.parse(
            "{\"content\":\"x\",\"prism_wordsInfo\":[{\"word\":\"a\",\"pos\":[]}]}"))
            .isInstanceOf(OcrParseException.class)
            .hasMessageContaining("坐标");

        assertThatThrownBy(() -> OcrParser.parse("not-json"))
            .isInstanceOf(OcrParseException.class);
    }

    @Test
    @DisplayName("桩实现的结果能解析出两行——离线也能验收「图片 → 文字」这一段")
    void stubResultIsParsable() {
        OcrParser.ParsedOcr parsed = OcrParser.parse(StubOcrApi.STUB_DATA);

        assertThat(parsed.lines()).hasSize(2);
        assertThat(parsed.lines().get(0)).contains("保证");
        assertThat(parsed.lines()).isEqualTo(List.of("我保证能减免利息。", "请你今天把款还上。"));
    }
}
