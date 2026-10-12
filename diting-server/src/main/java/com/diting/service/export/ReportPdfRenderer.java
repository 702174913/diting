package com.diting.service.export;

import java.util.List;

/**
 * 报告 PDF 渲染（开发文档 §9）。
 *
 * 契约：固定模板、固定字体、**不拉取任何远端资源**；文本一律按纯文本绘制（不做 HTML 解析），
 * 因此报告里的任何内容都无法注入标记或触发外部请求。
 *
 * 版面用「行 + 行内片段」表达：
 * - 命中处用**红色**绘制（标红），片段带 hit 标记；
 * - 行样式是显式枚举，**不再用字符串前缀当模板标记**——旧实现把 `#TITLE#` 这类内部标记
 *   当正文画进了 PDF（本轮修复）。
 */
public interface ReportPdfRenderer {

    /** 渲染报告为 PDF 字节；未配置字体时抛 NOT_CONFIGURED，绝不返回空 PDF */
    byte[] render(PdfReport report);

    /** 行样式：决定字号，不再靠正文里的前缀字符串 */
    enum Style {
        TITLE, SECTION, BODY, MUTED, CALLOUT
    }

    /** 行内片段：hit=true 红色；accent=true 蓝色粗体（统计数据）；其余为正文色。 */
    record Span(String text, boolean hit, boolean accent) {

        public Span(String text, boolean hit) {
            this(text, hit, false);
        }

        public static Span blue(String text) {
            return new Span(text, false, true);
        }

        public static Span of(String text) {
            return new Span(text, false);
        }

        /** 标红片段：命中/违规处用它 */
        public static Span red(String text) {
            return new Span(text, true);
        }
    }

    /** 一行：若干片段 + 样式 */
    record Line(List<Span> spans, Style style) {

        public static Line title(String text) {
            return new Line(List.of(Span.of(text)), Style.TITLE);
        }

        public static Line section(String text) {
            return new Line(List.of(Span.of(text)), Style.SECTION);
        }

        public static Line body(String text) {
            return new Line(List.of(Span.of(text)), Style.BODY);
        }

        public static Line of(List<Span> spans) {
            return new Line(spans, Style.BODY);
        }
    }

    /**
     * 渲染所需的全部内容（由服务端组装，含版本、时间、规则依据与命中标识）。
     * sections 里的文本已经按调用方的脱敏策略处理过（等长掩码，偏移不变）；
     * footers 是免责声明之后的补充声明；实际每页页脚由模板统一绘制。
     * summarySpans 保留结论中的蓝色统计数据与高风险红色标记。
     */
    record PdfReport(String reportNo, String generatedAt, String versionLabel, String summary,
                     List<Section> sections, List<String> footers, List<Span> summarySpans) {
        public PdfReport(String reportNo, String generatedAt, String versionLabel, String summary,
                         List<Section> sections, List<String> footers) {
            this(reportNo, generatedAt, versionLabel, summary, sections, footers,
                List.of(Span.of(summary)));
        }
    }

    /** violation=true 为动态违规点；其余章节保留在正文之后的附录。 */
    record Section(String title, List<Line> lines, boolean violation) {
        public Section(String title, List<Line> lines) {
            this(title, lines, false);
        }
    }
}
