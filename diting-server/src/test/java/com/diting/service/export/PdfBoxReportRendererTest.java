package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.service.admin.RedactionService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXPORT-01/03/06：PDF 渲染。
 * 直接对生成的 PDF 做文本抽取与内容流检查，验证中文、分页、注入内容、脱敏与**标红**的落盘效果。
 */
class PdfBoxReportRendererTest {

    /** 内置字体（随 jar 交付，OFL 授权）：测试不再依赖机器上的系统字体 */
    private static final String FONT = "classpath:fonts/NotoSansSC-Regular.ttf";
    /** 页脚声明：需求指定文案，必须在 PDF 底部出现 */
    private static final List<String> FOOTERS = List.of(
        "本服务仅记录通话内容，不构成法律意见，",
        "不作为处理结果承诺。",
        "所引规范条文请以现行有效版本为准。");

    private static PdfBoxReportRenderer renderer() {
        return new PdfBoxReportRenderer(FONT);
    }

    private static String extract(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * 内容流里是否出现「红色填充」：PDFBox 3 用 `DeviceRGB cs` + `r g b sc` 写颜色
     * （旧写法 `rg` 也接受），这里只认三个分量里红明显占优的那种。
     */
    private static boolean hasRedFill(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDFStreamParser parser = new PDFStreamParser(document.getPage(index));
                List<Object> tokens = parser.parse();
                parser.close();
                for (int i = 0; i + 3 < tokens.size(); i++) {
                    if (!(tokens.get(i) instanceof COSNumber red)
                        || !(tokens.get(i + 1) instanceof COSNumber green)
                        || !(tokens.get(i + 2) instanceof COSNumber blue)
                        || !(tokens.get(i + 3) instanceof Operator operator)) {
                        continue;
                    }
                    boolean colorOperator = "rg".equals(operator.getName()) || "sc".equals(operator.getName());
                    if (colorOperator
                        && red.floatValue() > 0.5f && green.floatValue() < 0.3f && blue.floatValue() < 0.3f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ReportPdfRenderer.PdfReport report(List<ReportPdfRenderer.Line> sentenceLines) {
        return new ReportPdfRenderer.PdfReport("DT-2026-000123", "2026-09-24T14:00:00",
            "报告版本 v1 · 规则集合 version=3",
            "命中 1 句 · 违规 2 项 · 风险分 30（阈值 60）",
            List.of(new ReportPdfRenderer.Section("逐句判读", sentenceLines),
                new ReportPdfRenderer.Section("命中与依据", List.of(
                    ReportPdfRenderer.Line.of(List.of(
                        ReportPdfRenderer.Span.red("● "),
                        ReportPdfRenderer.Span.of("hit 规则《禁用承诺》扣 30：命中关键词")))))),
            FOOTERS);
    }

    private static ReportPdfRenderer.Line plainLine(String text) {
        return ReportPdfRenderer.Line.body(text);
    }

    @Test
    @DisplayName("EXPORT-01 中文可读：正文、规则依据与页脚声明都被抽出")
    void rendersChinese() throws Exception {
        byte[] pdf = renderer().render(report(List.of(plainLine("[1] AGENT | 你好，我是某某公司的客服。"))));

        String text = extract(pdf);
        assertThat(text).contains("质检记录").contains("你好，我是某某公司的客服。")
            .contains("禁用承诺").contains("不构成法律意见");
    }

    @Test
    @DisplayName("EXPORT-01 页脚声明完整落在 PDF 底部（需求指定文案）")
    void footerDisclaimerIsRendered() throws Exception {
        String text = extract(renderer().render(report(List.of(plainLine("[1] AGENT | 一句话。")))));

        for (String footer : FOOTERS) {
            assertThat(text).contains(footer);
        }
        // 「本服务仅记录通话内容」不能退化成旧文案
        assertThat(text).contains("本服务仅记录通话内容，不构成法律意见，")
            .doesNotContain("本记录仅对通话内容进行转写与标注");
    }

    @Test
    @DisplayName("版面标记不外泄：PDF 正文里不出现 #TITLE# / #SECTION# 这类内部标记")
    void templateMarkersNeverLeak() throws Exception {
        String text = extract(renderer().render(report(List.of(plainLine("[1] AGENT | 一句话。")))));

        assertThat(text).doesNotContain("#TITLE#").doesNotContain("#SECTION#")
            .doesNotContain("#");
    }

    @Test
    @DisplayName("标红：命中片段用红色绘制，未命中内容不标红")
    void hitSpansAreRed() throws Exception {
        ReportPdfRenderer.Line hitLine = ReportPdfRenderer.Line.of(List.of(
            ReportPdfRenderer.Span.of("[1] AGENT | "),
            ReportPdfRenderer.Span.of("我"),
            ReportPdfRenderer.Span.red("保证"),
            ReportPdfRenderer.Span.of("能减免。")));
        byte[] pdf = renderer().render(report(List.of(hitLine)));

        assertThat(extract(pdf)).contains("我保证能减免。");
        assertThat(hasRedFill(pdf)).as("命中片段必须是红色填充").isTrue();

        // 没有任何标红片段时，正文里不该出现红色（说明颜色是按片段切换的，而不是整页涂红）
        byte[] plain = renderer().render(new ReportPdfRenderer.PdfReport("DT-2026-000123",
            "2026-09-24T14:00:00", "报告版本 v1 · 规则集合 version=3", "命中 0 句 · 违规 0 项",
            List.of(new ReportPdfRenderer.Section("逐句判读",
                List.of(plainLine("[1] AGENT | 我保证能减免。")))), FOOTERS));
        assertThat(hasRedFill(plain)).as("未命中时不应标红").isFalse();
    }

    @Test
    @DisplayName("EXPORT-01 长文自动分页，不裁切内容（含折行后的逐行分页）")
    void longReportPaginates() throws Exception {
        List<ReportPdfRenderer.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            lines.add(plainLine("[%d] AGENT | 第 %d 句用于验证分页与折行，中文必须完整显示。".formatted(i, i)));
        }
        byte[] pdf = renderer().render(report(lines));

        assertThat(pages(pdf)).isGreaterThan(1);
        String text = extract(pdf);
        assertThat(text).contains("第 1 句").contains("第 200 句");
        // 折行与分页都不能丢内容：最后一句的句号也要在
        assertThat(text).contains("中文必须完整显示。");
    }

    @Test
    @DisplayName("EXPORT-03 注入内容按纯文本落盘：PDF 内不出现链接与脚本标记")
    void injectionIsPlainText() throws Exception {
        String payload = "<script>alert('x')</script><img src=\"http://evil.example.com/x.png\">";
        byte[] pdf = renderer().render(report(List.of(plainLine("[1] AGENT | " + payload))));

        // 文本里能看到原样内容（用户需要看到对方到底说了什么），但 PDF 结构中不含外部资源引用
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1))
            .doesNotContain("/URI").doesNotContain("evil.example.com")
            .doesNotContain("/JavaScript").doesNotContain("/EmbeddedFile");
        assertThat(extract(pdf)).contains("<script>");
    }

    @Test
    @DisplayName("EXPORT-06 脱敏文本等长掩码：渲染后仍是掩码形态，长度不变")
    void redactedTextStaysMasked() throws Exception {
        String original = "手机号是 13812345678，身份证 11010119900307721X。";
        String redacted = RedactionService.redact(original);

        assertThat(redacted).hasSameSizeAs(original);
        byte[] pdf = renderer().render(report(List.of(plainLine("[1] AGENT | " + redacted))));
        String text = extract(pdf);
        assertThat(text).doesNotContain("13812345678").doesNotContain("11010119900307721X");
        assertThat(text).contains("138****5678");
    }

    @Test
    @DisplayName("字体不可用时明确 503，不产出空 PDF")
    void missingFontFails() {
        PdfBoxReportRenderer renderer = new PdfBoxReportRenderer("/no/such/font.ttf");

        assertThat(renderer.fontAvailable()).isFalse();
        assertThatThrownBy(() -> renderer.render(report(List.of(plainLine("x")))))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_CONFIGURED);
    }
}
