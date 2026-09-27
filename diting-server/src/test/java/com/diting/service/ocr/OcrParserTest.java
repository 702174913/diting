package com.diting.service.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 结果解析：两套官方结构都要认（契约核验日期 2026-09-27）。
 *
 * - RecognizeAllText（当前默认，Type=General）：`Data.Content` 全文 + `SubImages[].BlockInfo.BlockDetails[]`，
 *   General 不返回坐标，所以阅读顺序只看官方换行与 BlockId；
 * - RecognizeGeneral（回滚开关 api=general）：`prism_wordsInfo[]` 词块 + 坐标，按坐标归并行。
 *
 * 两套接口解析出来的**行必须一致**（下面用两份桩数据锁住）：换接口不该改变判读口径。
 */
class OcrParserTest {

    @Test
    @DisplayName("统一识别：Content 有换行就按换行切行")
    void allTextSplitsContentByLineBreaks() {
        String data = """
            {"Content":"我保证能减免利息。\\n请你今天把款还上。",
             "SubImageCount":1,
             "SubImages":[{"SubImageId":0,"Type":"general","BlockInfo":{"BlockCount":2,"BlockDetails":[
               {"BlockId":0,"BlockContent":"我保证能减免利息。"},
               {"BlockId":1,"BlockContent":"请你今天把款还上。"}]}}],
             "Height":200,"Width":400}
            """;

        OcrParser.ParsedOcr parsed = OcrParser.parse(data);

        assertThat(parsed.lines()).containsExactly("我保证能减免利息。", "请你今天把款还上。");
        assertThat(parsed.content()).contains("减免利息");
    }

    @Test
    @DisplayName("统一识别：Content 没有换行时退回按文字块切（块粒度更细，且不会从句子中间切）")
    void allTextFallsBackToBlocksWhenContentHasNoLineBreak() {
        String data = """
            {"Content":"我保证能减免利息。请你今天把款还上。",
             "SubImages":[{"SubImageId":0,"BlockInfo":{"BlockDetails":[
               {"BlockId":1,"BlockContent":"请你今天把款还上。"},
               {"BlockId":0,"BlockContent":"我保证能减免利息。"}]}}]}
            """;

        // 文字块按 BlockId 排序（官方编号即阅读顺序），不依赖传入顺序
        assertThat(OcrParser.parse(data).lines())
            .containsExactly("我保证能减免利息。", "请你今天把款还上。");
    }

    @Test
    @DisplayName("统一识别：SDK 模型序列化出来是 camelCase，官方报文是 PascalCase——两种都要认")
    void allTextFieldNamesAreCaseInsensitive() {
        String camel = """
            {"content":"我保证能减免利息。\\n请你今天把款还上。",
             "subImages":[{"subImageId":0,"blockInfo":{"blockDetails":[
               {"blockId":0,"blockContent":"我保证能减免利息。"},
               {"blockId":1,"blockContent":"请你今天把款还上。"}]}}]}
            """;

        assertThat(OcrParser.parse(camel).lines())
            .containsExactly("我保证能减免利息。", "请你今天把款还上。");
    }

    @Test
    @DisplayName("中文之间的排版空格要去掉（规则按中文短语匹配），英文单词之间的空格保留")
    void wideTextSpacingIsNormalized() {
        String data = """
            {"Content":"我 保证 能减免利息。\\niPhone 12 已签收"}
            """;

        assertThat(OcrParser.parse(data).lines())
            .containsExactly("我保证能减免利息。", "iPhone 12 已签收");
    }

    @Test
    @DisplayName("统一识别：图上没有文字 → 空行集合（上层判 EMPTY），不是解析错误")
    void allTextEmptyIsNotAnError() {
        assertThat(OcrParser.parse("{\"Content\":\"\",\"SubImageCount\":0,\"SubImages\":[]}").lines()).isEmpty();
        assertThat(OcrParser.parse("{\"Content\":\"   \\n  \"}").lines()).isEmpty();
    }

    @Test
    @DisplayName("旧接口：按坐标排出阅读顺序，并把同一行的词合成一行")
    void generalOrdersByReadingOrder() {
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
    @DisplayName("旧接口：英文与数字之间补空格，中文直接连写")
    void generalJoinsLatinWordsWithSpace() {
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
    @DisplayName("旧接口：图上没有文字 → 空行集合，不报错")
    void generalEmptyWordsMeansNoText() {
        assertThat(OcrParser.parse("{\"content\":\"\",\"prism_wordsInfo\":[]}").lines()).isEmpty();
    }

    @Test
    @DisplayName("结构非法：明确抛解析异常，不猜字段")
    void malformedDataFails() {
        // 两套结构都不匹配（既没有 Content/SubImages，也没有 prism_wordsInfo）
        assertThatThrownBy(() -> OcrParser.parse("{\"foo\":1}"))
            .isInstanceOf(OcrParseException.class)
            .hasMessageContaining("无法识别的 OCR 返回结构");

        assertThatThrownBy(() -> OcrParser.parse(
            "{\"content\":\"x\",\"prism_wordsInfo\":[{\"word\":\"a\",\"pos\":[]}]}"))
            .isInstanceOf(OcrParseException.class)
            .hasMessageContaining("坐标");

        assertThatThrownBy(() -> OcrParser.parse("not-json"))
            .isInstanceOf(OcrParseException.class);
        assertThatThrownBy(() -> OcrParser.parse("[]"))
            .isInstanceOf(OcrParseException.class)
            .hasMessageContaining("对象结构");
    }

    @Test
    @DisplayName("桩实现（两个接口）解析结果一致——离线也能验收「图片 → 文字」这一段")
    void stubResultsAreParsableAndEquivalent() {
        List<String> expected = List.of("我保证能减免利息。", "请你今天把款还上。");

        assertThat(OcrParser.parse(StubOcrApi.STUB_DATA).lines()).isEqualTo(expected);
        assertThat(OcrParser.parse(StubOcrApi.STUB_LEGACY_DATA).lines()).isEqualTo(expected);
    }
}
