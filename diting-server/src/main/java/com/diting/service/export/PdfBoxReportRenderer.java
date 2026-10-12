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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 商务模板：A4、嵌入字体、纯文本绘制，动态内容按实际字宽折行并分页。 */
public class PdfBoxReportRenderer implements ReportPdfRenderer {
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float LEFT = 68.15f;
    private static final float RIGHT = 527.35f;
    private static final float BODY_X = 79.5f;
    private static final float BOTTOM = 60f;
    private static final Color NAVY = new Color(0x20, 0x3D, 0x64);
    private static final Color BLUE = new Color(0x2E, 0x70, 0xB5);
    private static final Color RED = new Color(0xD8, 0x36, 0x28);
    private static final Color DARK = new Color(0x28, 0x30, 0x3D);
    private static final Color GRAY = new Color(0x62, 0x6D, 0x7F);
    private static final Color MUTED = new Color(0x8A, 0x95, 0xA6);
    private static final Color BORDER = new Color(0xD1, 0xD7, 0xDF);
    private static final Color PALE = new Color(0xE7, 0xEF, 0xF8);
    private static final String CLASSPATH_PREFIX = "classpath:";

    static final List<String> NOTICES = List.of(
        "一、本报告由\"递听\"平台基于您提供的通话录音及平台预设规则库自动生成，属于技术分析文件，不构成法律意见或法律咨询。",
        "二、报告中\"命中\"\"检测到\"等表述，仅指录音内容与平台规则库的形式比对结果，不代表对任何行为或主体的法律定性。",
        "三、本报告不能作为法律意见直接提交给监管机构或人民法院，不能作为投诉、举报、诉讼的材料或依据。本报告仅供您本人了解本次通话情况使用。",
        "四、本报告结论仅基于您提供的录音，平台不核实录音的真实性与合法性，亦不对债务性质作出判断。",
        "五、如您需就相关事宜作出决定或采取行动，请咨询执业律师。",
        "六、检测方法：本报告采用\"语音转写、规则比对\"的技术路径，即先将录音转为文字，再与平台规则库进行形式化比对。全过程不包含人工法律判断，不进行法律定性。",
        "七、已知局限：语音识别可能受方言、口音、背景噪音、语速、口语化表达、数字读法等因素影响，存在误识别可能；反讽、隐喻、省略句式等表达，语义模型可能无法准确还原原意；说话人分离可能产生误差；规则库覆盖范围有限，未命中不代表通话内容合规；本报告未对债务性质进行核实。",
        "八、复核与申诉：如对报告内容有异议，可通过 APP\"报告反馈\"入口提交。"
    );
    static final List<String> DISCLAIMERS = List.of(
        "一、本报告不能作为法律意见直接提交给监管机构或人民法院，不能作为投诉、举报或诉讼的材料或依据。",
        "二、本报告仅供您本人了解本次通话情况使用。请在合法范围内使用，不得用于胁迫他人、虚构事实、恶意投诉、伪造证据或其他违法用途。",
        "三、因违反上述限制使用本报告产生的一切后果，由您自行承担。",
        "本平台不发起、不代理、不组织任何投诉、举报、协商行为，不提供投诉文书代拟服务，不代为与任何机构沟通。",
        "本报告是否被任何机构或司法机关采纳，不由本平台决定，本平台亦不作任何承诺或保证。"
    );

    private final String fontPath;
    private final File fontFile;
    private final String classpathResource;

    public PdfBoxReportRenderer(String fontPath) {
        this.fontPath = fontPath == null ? "" : fontPath.trim();
        this.classpathResource = this.fontPath.startsWith(CLASSPATH_PREFIX)
            ? this.fontPath.substring(CLASSPATH_PREFIX.length()) : null;
        this.fontFile = classpathResource == null ? new File(this.fontPath) : null;
    }

    public boolean fontAvailable() {
        return classpathResource != null
            ? getClass().getResource("/" + classpathResource) != null
            : fontFile.isFile() && fontFile.canRead();
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        if (classpathResource == null) return PDType0Font.load(document, fontFile);
        try (InputStream in = getClass().getResourceAsStream("/" + classpathResource)) {
            if (in == null) throw new IOException("jar 内字体不存在：" + classpathResource);
            return PDType0Font.load(document, in);
        }
    }

    @Override
    public byte[] render(PdfReport report) {
        if (!fontAvailable()) {
            throw new BizException(ResultCode.NOT_CONFIGURED, "导出字体不可用：" + fontPath);
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadFont(document);
            PDFont bold;
            try (InputStream in = getClass().getResourceAsStream("/fonts/NotoSansSC-Bold.ttf")) {
                if (in == null) throw new IOException("商务模板粗体字体缺失");
                bold = PDType0Font.load(document, in);
            }
            try (Canvas canvas = new Canvas(document, font, bold, report)) {
                canvas.render();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "生成 PDF 失败：" + e.getMessage());
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /** 所有游标、字体和页面状态局限于单次 render，单例渲染器可并发使用。 */
    private static final class Canvas implements AutoCloseable {
        private final PDDocument document;
        private final PDFont font;
        private final PDFont bold;
        private final PdfReport report;
        private PDPageContentStream stream;
        private float y;

        Canvas(PDDocument document, PDFont font, PDFont bold, PdfReport report) throws IOException {
            this.document = document;
            this.font = font;
            this.bold = bold;
            this.report = report;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) { footer(); stream.close(); }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = 767.79f;
        }

        private void ensure(float height) throws IOException {
            if (y - height < BOTTOM) newPage();
        }

        void render() throws IOException {
            rect(60.75f, 746.8f, 4.5f, 27.5f, NAVY);
            text("递听 · 录音质检报告", 75.25f, 752.14f, 20, NAVY, bold);
            text("VOICE QUALITY INSPECTION REPORT", 75.25f, 732.24f, 8.5f, MUTED, font);
            y = 696.54f;
            heading("报告信息");
            table();
            y = 581.69f;
            heading("重要提示");
            for (int i = 0; i < NOTICES.size(); i++) {
                paragraph(List.of(Span.of(NOTICES.get(i))), i == 2 ? Style.CALLOUT : Style.MUTED,
                    BODY_X, 9.5f, 19.4f, i == 2 ? NAVY : GRAY);
            }
            y -= 14.75f;
            heading("检测结论");
            paragraph(report.summarySpans(), Style.BODY, BODY_X, 10.5f, 22.85f, DARK);
            y -= 13.65f;
            heading("违规点明细");
            paragraph(List.of(Span.of("债务性质无法从本次通话内容中判断，故本报告不对债务性质及行为合法性作出判断。")),
                Style.MUTED, BODY_X, 9.5f, 19.4f, MUTED);
            // 模板封面与明细分开；违规点数量和每项内容全部来自报告，不固定为三项。
            newPage();
            List<Section> violations = report.sections().stream().filter(Section::violation).toList();
            if (violations.isEmpty()) {
                paragraph(List.of(Span.of("本次检测未发现规则命中项。未命中不代表通话内容合规。")),
                    Style.MUTED, BODY_X, 9.5f, 19.4f, MUTED);
            }
            for (Section section : violations) {
                ensure(60);
                rect(65.5f, y - 4, RIGHT - 65.5f, 15.5f, PALE);
                rect(65.5f, y - 4, 2.5f, 15.5f, NAVY);
                text(section.title(), BODY_X, y, 11, NAVY, bold);
                y -= 21.15f;
                for (Line line : section.lines()) {
                    paragraph(line.spans(), line.style(), 86.6f, 9.5f, 19.4f,
                        line.style() == Style.MUTED ? MUTED : DARK);
                }
                y -= 8;
            }
            y -= 12;
            heading("使用限制与免责声明");
            for (int i = 0; i < DISCLAIMERS.size(); i++) {
                paragraph(List.of(Span.of(DISCLAIMERS.get(i))), Style.BODY,
                    BODY_X, 9.5f, 19.4f, i < 3 ? DARK : GRAY);
            }
            for (String footer : report.footers()) {
                paragraph(List.of(Span.of(footer)), Style.MUTED, BODY_X, 9.5f, 19.4f, GRAY);
            }
            List<Section> appendices = report.sections().stream().filter(section -> !section.violation()).toList();
            if (!appendices.isEmpty()) {
                newPage();
                heading("附录 · 质检记录");
                paragraph(List.of(Span.of(report.versionLabel())), Style.MUTED, BODY_X, 9, 19.4f, MUTED);
                for (Section section : appendices) {
                    y -= 12;
                    heading(section.title());
                    for (Line line : section.lines()) {
                        paragraph(line.spans(), line.style(), BODY_X, 9.5f, 19.4f, DARK);
                    }
                }
            }
        }

        private void table() throws IOException {
            float x = 62.75f, top = 674.9f, half = (RIGHT - x) / 2;
            for (int i = 0; i < 2; i++) {
                float bottom = top - (i + 1) * 32;
                rect(x, bottom, half, 32, new Color(0xF3, 0xF4, 0xF6));
                outline(x, bottom, RIGHT - x, 32);
                rule(x + half, bottom, x + half, bottom + 32);
                text(i == 0 ? "报告编号" : "生成时间", LEFT, bottom + 14, 10, NAVY, bold);
                String value = i == 0 ? safe(report.reportNo()) : formatTime(report.generatedAt());
                float size = 10;
                while (width(font, value, size) > half - 12 && size > 6) size -= 0.25f;
                text(value, x + half + 5.4f, bottom + 14, size, DARK, font);
            }
        }

        private static String formatTime(String value) {
            try {
                var time = java.time.LocalDateTime.parse(value);
                return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日 HH:mm:ss"));
            } catch (RuntimeException e) { return safe(value); }
        }

        private void heading(String title) throws IOException {
            ensure(54);
            text(title, LEFT, y, 13, NAVY, bold);
            rule(LEFT, y - 8, RIGHT, y - 8);
            y -= 31.35f;
        }

        private void paragraph(List<Span> spans, Style style, float x, float size, float leading, Color color)
            throws IOException {
            PDFont face = style == Style.CALLOUT ? bold : font;
            List<List<Span>> rows = layout(spans, face, size, RIGHT - x);
            for (List<Span> row : rows) {
                ensure(leading);
                if (style == Style.CALLOUT) {
                    rect(LEFT + 1.5f, y - 4, RIGHT - LEFT - 1.5f, leading, PALE);
                    rect(LEFT + 1.5f, y - 4, 2, leading, BLUE);
                }
                float cursor = x;
                for (Span span : row) {
                    Color ink = span.hit() ? RED : span.accent() ? BLUE : color;
                    PDFont spanFont = span.accent() || style == Style.CALLOUT ? bold : font;
                    text(span.text(), cursor, y, size, ink, spanFont);
                    cursor += width(spanFont, span.text(), size);
                }
                y -= leading;
            }
            y -= 4;
        }

        private List<List<Span>> layout(List<Span> spans, PDFont face, float size, float maxWidth)
            throws IOException {
            List<List<Span>> rows = new ArrayList<>();
            List<Span> row = new ArrayList<>();
            float used = 0;
            for (Span span : spans == null ? List.<Span>of() : spans) {
                String value = safe(span.text()).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
                StringBuilder run = new StringBuilder();
                PDFont spanFont = span.accent() ? bold : face;
                for (int offset = 0; offset < value.length();) {
                    int cp = value.codePointAt(offset);
                    offset += Character.charCount(cp);
                    if (cp == '\n') {
                        flush(row, run, span); rows.add(row); row = new ArrayList<>(); used = 0; continue;
                    }
                    if (Character.isISOControl(cp)) continue;
                    String piece = new String(Character.toChars(cp));
                    try { spanFont.encode(piece); } catch (IllegalArgumentException e) { piece = "□"; }
                    float advance = width(spanFont, piece, size);
                    // 数字串与紧随其后的闭标点一起考虑，避免把“60”拆开或让句号落在行首。
                    float punctuationWidth = 0;
                    int next = offset;
                    int start = offset - Character.charCount(cp);
                    if (Character.isDigit(cp) && (start == 0 || !Character.isDigit(value.codePointBefore(start)))) {
                        while (next < value.length() && Character.isDigit(value.codePointAt(next))) {
                            int digit = value.codePointAt(next);
                            punctuationWidth += width(spanFont, new String(Character.toChars(digit)), size);
                            next += Character.charCount(digit);
                        }
                    }
                    while (next < value.length()) {
                        int following = value.codePointAt(next);
                        if ("，。！？：；、）】》〉」』〕］｝,.!?;:)]}".indexOf(following) < 0) break;
                        punctuationWidth += width(spanFont, new String(Character.toChars(following)), size);
                        next += Character.charCount(following);
                    }
                    // 极长数字仍按字宽折行，不能因不可拆分的 token 超出页边界。
                    if (advance + punctuationWidth > maxWidth) punctuationWidth = 0;
                    if (used + advance + punctuationWidth > maxWidth && used > 0) {
                        flush(row, run, span); rows.add(row); row = new ArrayList<>(); used = 0;
                    }
                    run.append(piece);
                    used += advance;
                }
                flush(row, run, span);
            }
            rows.add(row);
            return rows;
        }

        private static void flush(List<Span> row, StringBuilder run, Span source) {
            if (!run.isEmpty()) {
                row.add(new Span(run.toString(), source.hit(), source.accent()));
                run.setLength(0);
            }
        }

        private void footer() throws IOException {
            String value = "递听 · 录音质检报告    |    " + safe(report.reportNo()) + "    |    第 "
                + document.getNumberOfPages() + " 页";
            text(value, (WIDTH - width(font, value, 8)) / 2, 39.04f, 8, MUTED, font);
        }

        private static float width(PDFont font, String value, float size) throws IOException {
            return font.getStringWidth(safe(value)) / 1000 * size;
        }

        private void text(String value, float x, float baseline, float size, Color color, PDFont face)
            throws IOException {
            stream.beginText(); stream.setFont(face, size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline); stream.showText(safe(value)); stream.endText();
        }

        private void rect(float x, float bottom, float width, float height, Color color) throws IOException {
            stream.setNonStrokingColor(color); stream.addRect(x, bottom, width, height); stream.fill();
        }

        private void rule(float x1, float y1, float x2, float y2) throws IOException {
            stream.setStrokingColor(BORDER); stream.setLineWidth(0.75f);
            stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke();
        }

        private void outline(float x, float y, float width, float height) throws IOException {
            stream.setStrokingColor(BORDER); stream.setLineWidth(0.75f);
            stream.addRect(x, y, width, height); stream.stroke();
        }

        @Override public void close() throws IOException {
            if (stream != null) { footer(); stream.close(); stream = null; }
        }
    }
}
