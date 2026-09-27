package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFBox 实现（Apache-2.0）。
 *
 * 版面固定：A4、页边距固定、正文 10.5pt、行高固定；超出页面自动分页（**按绘制行分页**，
 * 折行后的每一行也参与分页判断，长句不会溢出到页边距外）。
 * 字体从配置路径加载（需持有合法授权的中文字体），并**嵌入子集**——否则中文在别的机器上会缺字。
 *
 * 标红：命中的片段（{@link ReportPdfRenderer.Span#hit()}）用红色填充绘制，其余用黑色；
 * 颜色切换按片段生效，折行后仍保持。
 */
public class PdfBoxReportRenderer implements ReportPdfRenderer {

    private static final float MARGIN = 48f;
    private static final float TITLE_SIZE = 16f;
    private static final float SECTION_SIZE = 12f;
    private static final float BODY_SIZE = 10.5f;
    private static final float LINE_HEIGHT = 16f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();

    /** 标红用正红：与界面高亮一致，打印成灰度也够深 */
    private static final Color HIT_COLOR = new Color(0xC0, 0x1B, 0x1B);
    private static final Color TEXT_COLOR = Color.BLACK;

    private final File fontFile;
    private final float maxWidth;

    public PdfBoxReportRenderer(String fontPath) {
        this.fontFile = new File(fontPath);
        this.maxWidth = PAGE_WIDTH - MARGIN * 2;
    }

    public boolean fontAvailable() {
        return fontFile.isFile() && fontFile.canRead();
    }

    @Override
    public byte[] render(PdfReport report) {
        if (!fontAvailable()) {
            throw new BizException(ResultCode.NOT_CONFIGURED,
                "导出字体未配置或不可读：请把 diting.export.font-path 指向持有合法授权的中文字体");
        }
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = PDType0Font.load(document, fontFile);
            List<Line> lines = flatten(report);
            PDPage page = newPage(document);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = PAGE_HEIGHT - MARGIN;
            for (Line line : lines) {
                float size = sizeOf(line);
                for (List<Span> row : layout(font, line.spans(), size)) {
                    if (y < MARGIN + LINE_HEIGHT) {
                        // 放不下就开新页，不压缩、不裁切
                        stream.close();
                        page = newPage(document);
                        stream = new PDPageContentStream(document, page);
                        y = PAGE_HEIGHT - MARGIN;
                    }
                    y = writeRow(stream, font, row, size, y);
                }
            }
            stream.close();
            document.save(out);
            return out.toByteArray();
        }
        catch (BizException e) {
            throw e;
        }
        catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "生成 PDF 失败：" + e.getMessage());
        }
    }

    /** 绘制一个排版行：按片段切换颜色，片段内的宽度按字体实际宽度累加 */
    private float writeRow(PDPageContentStream stream, PDFont font, List<Span> row, float size, float y)
        throws IOException {
        float x = MARGIN;
        for (Span span : row) {
            String text = span.text() == null ? "" : span.text();
            if (text.isEmpty()) {
                continue;
            }
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(span.hit() ? HIT_COLOR : TEXT_COLOR);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
            x += font.getStringWidth(text) / 1000 * size;
        }
        return y - LINE_HEIGHT;
    }

    /**
     * 折行：把片段序列按可用宽度切成若干行（**保留每个片段的标红标记**，同一片段被切开后
     * 两半都保持原颜色）。空白行返回一个空行，保持段落间距。
     */
    private List<List<Span>> layout(PDFont font, List<Span> spans, float size) throws IOException {
        List<List<Span>> rows = new ArrayList<>();
        List<Span> row = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        boolean pendingHit = false;
        boolean started = false;
        float width = 0f;
        for (Span span : spans == null ? List.<Span>of() : spans) {
            String text = span.text() == null ? "" : span.text();
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                String piece = new String(Character.toChars(codePoint));
                i += Character.charCount(codePoint);
                if (started && pendingHit != span.hit() && pending.length() > 0) {
                    row.add(new Span(pending.toString(), pendingHit));
                    pending.setLength(0);
                }
                pendingHit = span.hit();
                started = true;
                float pieceWidth = font.getStringWidth(piece) / 1000 * size;
                if (width + pieceWidth > maxWidth && (pending.length() > 0 || !row.isEmpty())) {
                    if (pending.length() > 0) {
                        row.add(new Span(pending.toString(), pendingHit));
                        pending.setLength(0);
                    }
                    rows.add(row);
                    row = new ArrayList<>();
                    width = 0f;
                }
                pending.append(piece);
                width += pieceWidth;
            }
        }
        if (pending.length() > 0) {
            row.add(new Span(pending.toString(), pendingHit));
        }
        rows.add(row);
        return rows;
    }

    private static float sizeOf(Line line) {
        return switch (line.style()) {
            case TITLE -> TITLE_SIZE;
            case SECTION -> SECTION_SIZE;
            case BODY -> BODY_SIZE;
        };
    }

    private static List<Line> flatten(PdfReport report) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.title(report.reportNo() + " 质检记录"));
        lines.add(Line.body("生成时间：" + report.generatedAt()));
        lines.add(Line.body(report.versionLabel()));
        lines.add(Line.body(""));
        for (String summaryLine : report.summary().split("\n")) {
            lines.add(Line.body(summaryLine));
        }
        lines.add(Line.body(""));
        for (Section section : report.sections()) {
            lines.add(Line.section(section.title()));
            lines.addAll(section.lines());
            lines.add(Line.body(""));
        }
        if (!report.footers().isEmpty()) {
            // 底部声明与正文之间留一行，避免看起来像正文的一部分
            lines.add(Line.body(""));
            for (String footer : report.footers()) {
                lines.add(Line.body(footer));
            }
        }
        return lines;
    }

    private static PDPage newPage(PDDocument document) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return page;
    }
}
