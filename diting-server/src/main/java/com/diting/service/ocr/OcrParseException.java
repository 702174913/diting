package com.diting.service.ocr;

/**
 * OCR 结果结构不符合预期（缺字段/坐标非法/不是 JSON）。
 * 与听悟的 TranscriptionParseException 对应：**明确报错**，不猜字段、不填 0 蒙混。
 */
public class OcrParseException extends RuntimeException {

    public OcrParseException(String message) {
        super(message);
    }

    public OcrParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
