package com.diting.service.ocr;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponse;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;

import java.io.ByteArrayInputStream;

/**
 * 阿里云实现（文字识别 ocr-api 2021-07-07，RecognizeGeneral）。
 *
 * 契约要点（官方文档核验日期 2026-09-24）：
 * - `Url` 与 `body` 二选一：这里走 **body（二进制）**——图片在私有对象存储里，
 *   交给云端拉 URL 会受桶策略与网络可达性限制；
 * - 单张 ≤10MB，格式 PNG/JPG/JPEG/BMP/GIF/TIFF/WebP（PDF 不支持）；
 * - 返回 `Data` 是 JSON **字符串**，解析在 {@link OcrParser}，这里只取字段。
 */
public class AliyunOcrApi implements OcrApi {

    private static final String DEFAULT_ENDPOINT = "ocr-api.cn-hangzhou.aliyuncs.com";

    private final Client client;

    public AliyunOcrApi(DitingProperties props) throws Exception {
        DitingProperties.Aliyun aliyun = props.getAliyun();
        String endpoint = aliyun.getOcr() == null || aliyun.getOcr().getEndpoint() == null
            || aliyun.getOcr().getEndpoint().isBlank()
            ? DEFAULT_ENDPOINT
            : aliyun.getOcr().getEndpoint().trim();
        this.client = new Client(new Config()
            .setAccessKeyId(aliyun.getAccessKeyId())
            .setAccessKeySecret(aliyun.getAccessKeySecret())
            .setEndpoint(endpoint));
    }

    @Override
    public String recognize(byte[] image) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(image)) {
            RecognizeGeneralResponse response = client.recognizeGeneral(
                new RecognizeGeneralRequest().setBody(in));
            RecognizeGeneralResponseBody body = response == null ? null : response.getBody();
            if (body == null) {
                throw new IllegalStateException("OCR 未返回响应体");
            }
            if (body.getData() == null || body.getData().isBlank()) {
                // 只记错误码，不把整段响应写进异常与日志（可能含图片原文）
                throw new BizException(ResultCode.INTERNAL_ERROR,
                    "OCR 未识别出内容（code=" + body.getCode() + "）");
            }
            return body.getData();
        }
        catch (BizException e) {
            throw e;
        }
        catch (Exception e) {
            // 超时/网络中断：结果不确定，交给管线按次数上限重试
            throw new IllegalStateException("OCR 调用失败：" + e.getMessage(), e);
        }
    }
}
