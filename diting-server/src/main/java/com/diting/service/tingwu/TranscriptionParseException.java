package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

/** 转写结果结构不符合预期：显式失败，不做“猜字段/填 0”的兜底 */
public class TranscriptionParseException extends BizException {

    public TranscriptionParseException(String message) {
        super(ResultCode.UNPROCESSABLE, message);
    }
}
