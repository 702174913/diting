package com.diting.service.ocr;

/**
 * 阿里云文字识别（OCR）端口。
 *
 * 与听悟端口同一套思路：真实调用需要云凭据，而「响应结构怎么解析」必须在没有凭据时也能验收。
 * 端口只负责发出请求并取回官方响应里的 `Data` 字段（JSON 字符串），
 * 字段解析一律在 {@link OcrParser} 里做，避免把契约判断散落在适配器里。
 */
public interface OcrApi {

    /**
     * 识别一张图片。
     *
     * @param image 图片二进制（官方限制 ≤10MB，调用方负责预检）
     * @return 官方响应里的 `Data` 字段：JSON 字符串，含 `content` 与 `prism_wordsInfo[]`
     * @throws com.diting.common.BizException 确定失败（未配置/图片不合规等）
     * @throws IllegalStateException           结果不确定（超时/网络中断），调用方可按次数上限重试
     */
    String recognize(byte[] image);
}
