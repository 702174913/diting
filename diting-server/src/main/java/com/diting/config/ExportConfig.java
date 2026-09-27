package com.diting.config;

import com.diting.service.export.NotConfiguredPdfRenderer;
import com.diting.service.export.PdfBoxReportRenderer;
import com.diting.service.export.ReportPdfRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PDF 渲染器选择：字体可用则用 PDFBox，否则 NOT_CONFIGURED（不返回空 PDF） */
@Slf4j
@Configuration
public class ExportConfig {

    @Bean
    public ReportPdfRenderer reportPdfRenderer(DitingProperties props) {
        PdfBoxReportRenderer renderer = new PdfBoxReportRenderer(props.getExport().getFontPath());
        if (renderer.fontAvailable()) {
            return renderer;
        }
        log.warn("导出字体不可用（{}）：PDF 导出会返回 503 NOT_CONFIGURED",
            props.getExport().getFontPath());
        return new NotConfiguredPdfRenderer("字体不可用：" + props.getExport().getFontPath());
    }
}
