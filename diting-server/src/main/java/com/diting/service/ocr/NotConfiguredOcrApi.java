package com.diting.service.ocr;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

/** 未配置 OCR 凭据：明确 503，不假装识别成功、不产出假文字 */
public class NotConfiguredOcrApi implements OcrApi {

    @Override
    public String recognize(byte[] image) {
        throw new BizException(ResultCode.NOT_CONFIGURED, "图片识别（OCR）未配置");
    }
}
