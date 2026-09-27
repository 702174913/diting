package com.diting.service.export;

import com.diting.dto.ViolationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 标红的**切片层**：把一句话按报告版本给出的 UTF-16 区间切成「普通 + 标红」片段。
 * 这一层最容易出错（越界、重叠、空区间），所以单独锁住；渲染层由 PdfBoxReportRendererTest 覆盖。
 */
class ExportHitSpansTest {

    private static String joined(List<ReportPdfRenderer.Span> spans) {
        StringBuilder builder = new StringBuilder();
        for (ReportPdfRenderer.Span span : spans) {
            builder.append(span.text());
        }
        return builder.toString();
    }

    private static List<String> redTexts(List<ReportPdfRenderer.Span> spans) {
        return spans.stream().filter(ReportPdfRenderer.Span::hit)
            .map(ReportPdfRenderer.Span::text).toList();
    }

    @Test
    @DisplayName("命中区间在中间：前后保持普通、命中部分标红，且文本不丢字")
    void redactsMiddleRange() {
        List<ReportPdfRenderer.Span> spans = ExportService.hitSpans("[1] AGENT | ", "我保证能减免利息。",
            List.of(new ViolationResponse.SentenceRange(3L, 1, 3)));

        assertThat(joined(spans)).isEqualTo("[1] AGENT | 我保证能减免利息。");
        assertThat(redTexts(spans)).containsExactly("保证");
    }

    @Test
    @DisplayName("多个不重叠区间分别标红；重叠区间不重复标")
    void handlesMultipleAndOverlappingRanges() {
        List<ReportPdfRenderer.Span> spans = ExportService.hitSpans("", "保证减免保证",
            List.of(new ViolationResponse.SentenceRange(1L, 0, 2),
                new ViolationResponse.SentenceRange(1L, 1, 3),
                new ViolationResponse.SentenceRange(2L, 4, 6)));

        assertThat(joined(spans)).isEqualTo("保证减免保证");
        // 0-2 与 1-3 取并集成 0-3；尾段 4-6 独立标红
        assertThat(redTexts(spans)).containsExactly("保证减", "保证");
    }

    @Test
    @DisplayName("越界/空/逆序区间不崩、不丢字，只退化为普通文本")
    void invalidRangesDegradeGracefully() {
        List<ReportPdfRenderer.Span> spans = ExportService.hitSpans("", "短句",
            List.of(new ViolationResponse.SentenceRange(1L, -5, 2),
                new ViolationResponse.SentenceRange(1L, 3, 1),
                new ViolationResponse.SentenceRange(1L, 99, 200)));

        assertThat(joined(spans)).isEqualTo("短句");
        assertThat(redTexts(spans)).containsExactly("短句");
    }

    @Test
    @DisplayName("没有命中区间时只有一个普通片段（PDF 不出现红色）")
    void noRangesStaysPlain() {
        List<ReportPdfRenderer.Span> spans = ExportService.hitSpans("[2] CUSTOMER | ", "好的。", List.of());

        assertThat(joined(spans)).isEqualTo("[2] CUSTOMER | 好的。");
        assertThat(redTexts(spans)).isEmpty();
    }

    @Test
    @DisplayName("整句命中：整段标红")
    void wholeSentenceHit() {
        List<ReportPdfRenderer.Span> spans = ExportService.hitSpans("", "全额减免",
            List.of(new ViolationResponse.SentenceRange(1L, 0, 4)));

        assertThat(redTexts(spans)).containsExactly("全额减免");
    }
}
