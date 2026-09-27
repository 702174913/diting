package com.diting.service.export;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

/** 字体或依赖不可用：导出返回 503，不产出空 PDF、也不冒充成功 */
public class NotConfiguredPdfRenderer implements ReportPdfRenderer {

    private final String reason;

    public NotConfiguredPdfRenderer(String reason) {
        this.reason = reason;
    }

    @Override
    public byte[] render(PdfReport report) {
        throw new BizException(ResultCode.NOT_CONFIGURED, "PDF 导出不可用：" + reason);
    }
}
