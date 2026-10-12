package com.diting.service.export;

import com.diting.dto.ReportDetailResponse;
import com.diting.dto.ReportLegalClauseResponse;
import com.diting.dto.SentenceResponse;
import com.diting.dto.ViolationResponse;
import com.diting.entity.RecognitionTask;
import com.diting.entity.TaskAsset;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 商务版测试样例仅验证排版和数据映射，不代表真实通话检测或真实规范。 */
class BusinessReportTemplateTest {
    private static final String FONT = "classpath:fonts/NotoSansSC-Regular.ttf";

    private static ReportDetailResponse detail(int count, boolean longClauses) {
        ReportDetailResponse detail = mock(ReportDetailResponse.class);
        List<ViolationResponse> violations = new ArrayList<>();
        List<SentenceResponse> sentences = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long sentenceId = i;
            String type = i == 1 ? "must-have" : i == 2 ? "require" : "hit";
            String text = "这是第 " + i + " 句测试原话，请勿将排版样例视为真实检测结果。";
            var ranges = List.of(new ViolationResponse.SentenceRange(sentenceId, 0, 2));
            sentences.add(new SentenceResponse(sentenceId, i, null, 1, "AGENT", 0L, 1000L,
                text, null, ranges, List.of(), List.of()));
            List<ReportLegalClauseResponse> clauses = i == 3 ? List.of() : List.of(
                new ReportLegalClauseResponse((long) i, "排版测试规范（虚构）", "示例条款 " + i,
                    longClauses
                        ? ("本段为长条文折行测试文本，不是真实法律规定。保留完整原文并检查页间连续性。".repeat(20)
                            + "条文结束标识 " + i)
                        : "本条仅为排版测试文本，非真实法律条文，不提供法律意见。"));
            violations.add(new ViolationResponse((long) i, (long) ((i - 1) % 3 + 1), "示例规则 " + i,
                type, "ALL", i == 1 ? null : sentenceId, i == 2 ? sentenceId : null,
                i == 1 ? List.of() : List.of(sentenceId), i == 1 ? List.of() : ranges, 10,
                i == 3 ? "" : "排版测试：" + type + " 规则比对证据说明。", clauses));
        }
        when(detail.reportNo()).thenReturn("DT-2026-000123");
        when(detail.durationSec()).thenReturn(522);
        when(detail.violations()).thenReturn(violations);
        when(detail.sentences()).thenReturn(sentences);
        when(detail.violationCount()).thenReturn(count);
        when(detail.riskScore()).thenReturn(65);
        when(detail.threshold()).thenReturn(60);
        return detail;
    }

    private static RecognitionTask task() {
        RecognitionTask task = new RecognitionTask();
        task.setOssObject("audio/test.m4a");
        return task;
    }

    private static ReportPdfRenderer.PdfReport report(ReportDetailResponse detail, boolean appendix) {
        var summary = ExportService.summarySpans(task(), detail, List.of());
        var sections = new ArrayList<>(ExportService.violationSections(detail));
        if (appendix) {
            sections.add(new ReportPdfRenderer.Section("逐句判读", detail.sentences().stream()
                .map(sentence -> ReportPdfRenderer.Line.of(ExportService.hitSpans(
                    "[" + sentence.sequence() + "] AGENT | ", sentence.text(), sentence.highlights())))
                .toList()));
        }
        return new ReportPdfRenderer.PdfReport(detail.reportNo(), "2026-09-30T10:02:17",
            "报告版本 v1 · 规则集合 version=3", summary.stream().map(ReportPdfRenderer.Span::text)
                .collect(java.util.stream.Collectors.joining()), sections,
            List.of("排版测试样例：规则与条文为虚构文本，非真实检测结果。", "所引规范条文请以现行有效版本为准。"), summary);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void generatesEveryViolationWithRealFieldsAndNoInventedBasis() {
        var sections = ExportService.violationSections(detail(5, false));
        assertThat(sections).hasSize(5).allMatch(ReportPdfRenderer.Section::violation);
        assertThat(sections.get(0).title()).isEqualTo("违规点 01");
        assertThat(sections.get(4).title()).isEqualTo("违规点 05");
        String first = lines(sections.get(0));
        assertThat(first).contains("未出现必需表述", "示例规则 1", "示例条款 1", "非真实法律条文")
            .doesNotContain("证据原话");
        assertThat(lines(sections.get(1))).contains("未检测到必需回应", "证据原话  [2]");
        assertThat(lines(sections.get(2))).contains("尚未绑定法律依据", "本违规点无附加说明")
            .doesNotContain("第 XX 条", "示例条款");
        assertThat(ExportService.violationSections(detail(0, false))).isEmpty();
    }

    private static String lines(ReportPdfRenderer.Section section) {
        return section.lines().stream().flatMap(line -> line.spans().stream()).map(ReportPdfRenderer.Span::text)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void summaryCountsDistinctRulesAndOnlyHighlightsPublishedHighRiskScores() {
        var detail = detail(5, false);
        var spans = ExportService.summarySpans(task(), detail, List.of());
        assertThat(spans.stream().map(ReportPdfRenderer.Span::text).collect(java.util.stream.Collectors.joining()))
            .contains("1 段通话", "08 分 42 秒", "3 项规则", "违规 5 项", "风险分 65（阈值 60）");
        assertThat(spans).anyMatch(span -> span.hit() && "65".equals(span.text()));
        when(detail.riskScore()).thenReturn(30);
        assertThat(ExportService.summarySpans(task(), detail, List.of())).noneMatch(ReportPdfRenderer.Span::hit);
        when(detail.riskScore()).thenReturn(null);
        when(detail.durationSec()).thenReturn(null);
        var unknown = ExportService.summarySpans(task(), detail, List.of());
        assertThat(unknown).noneMatch(ReportPdfRenderer.Span::hit);
        assertThat(unknown.stream().map(ReportPdfRenderer.Span::text).collect(java.util.stream.Collectors.joining()))
            .contains("风险分 未发布", "时长未知").doesNotContain("风险分 0", "08 分 42 秒");
        TaskAsset image = new TaskAsset(); image.setKind("IMAGE");
        RecognitionTask imageTask = new RecognitionTask(); imageTask.setMediaKind("IMAGE");
        var imageSummary = ExportService.summarySpans(imageTask, detail, List.of(image));
        assertThat(imageSummary.stream().map(ReportPdfRenderer.Span::text).collect(java.util.stream.Collectors.joining()))
            .contains("0 段通话、1 张图片").doesNotContain("合计");
    }

    @Test
    void coverMatchesTemplateStructureAndEveryPageHasAnActualPageNumber() throws Exception {
        byte[] pdf = new PdfBoxReportRenderer(FONT).render(report(detail(5, false), false));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1); stripper.setEndPage(1);
            String cover = stripper.getText(document);
            assertThat(cover).contains("递听 · 录音质检报告", "VOICE QUALITY INSPECTION REPORT", "报告信息",
                "报告编号", "2026 年 09 月 30 日 10:02:17", "重要提示", "检测结论", "违规点明细")
                .doesNotContain("违规点 01", "逐句判读");
            assertThat(cover.lines().filter(line -> !line.isBlank()).noneMatch(line ->
                "，。！？：；、）】》".indexOf(line.stripLeading().codePointAt(0)) >= 0))
                .as("封面自动折行不应让闭标点单独落在行首").isTrue();
            assertThat(cover).as("阈值数字不拆行").contains("60）");
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                stripper.setStartPage(i); stripper.setEndPage(i);
                assertThat(stripper.getText(document)).contains("第 " + i + " 页", "DT-2026-000123");
            }
        }
    }

    @Test
    void longClausesAndLargeViolationCountsPaginateWithoutTextLossOrFooterOverlap() throws Exception {
        byte[] pdf = new PdfBoxReportRenderer(FONT).render(report(detail(12, true), true));
        assertThat(text(pdf).replace("\n", "").replace("\r", ""))
            .contains("违规点 12", "条文结束标识 12", "使用限制与免责声明", "逐句判读", "第 12 句测试原话");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(4);
            // 直接检查 PDF 字符坐标（非浏览器）：正文与页脚均不能超出页面或重叠。
            new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertThat(position.getXDirAdj()).isBetween(60f, 530f);
                    assertThat(position.getYDirAdj()).isBetween(60f, 810f);
                    float baseline = position.getYDirAdj();
                    assertThat(baseline < HEIGHT_LIMIT || Math.abs(baseline - 802.85f) < 1f)
                        .as("字符应位于正文区域或指定页脚基线，不得侵入中间留白").isTrue();
                    super.processTextPosition(position);
                }
            }.getText(document);
        }
    }

    private static final float HEIGHT_LIMIT = 781.9f; // A4 841.89 - 正文保留底边 60

    @Test
    void writesPreviewArtifactsForManualInspection() throws Exception {
        Path directory = Path.of("target", "pdf-preview");
        Files.createDirectories(directory);
        Files.write(directory.resolve("business-report.pdf"),
            new PdfBoxReportRenderer(FONT).render(report(detail(5, false), true)));
        Files.write(directory.resolve("business-report-long.pdf"),
            new PdfBoxReportRenderer(FONT).render(report(detail(12, true), true)));
        Files.writeString(directory.resolve("README.txt"),
            "排版测试样例（虚构规则与条文，非真实检测结果）\n"
                + "business-report.pdf：5 项违规、3 项去重规则、含未绑定依据和逐句附录。\n"
                + "business-report-long.pdf：12 项违规、长法律原文，验证连续折行分页及页脚。\n");
    }
}
