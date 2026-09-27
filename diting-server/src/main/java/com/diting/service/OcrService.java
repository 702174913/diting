package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.service.ocr.NotConfiguredOcrApi;
import com.diting.service.ocr.OcrApi;
import com.diting.service.ocr.OcrParser;
import com.diting.service.storage.ImageDimensionProbe;
import com.diting.service.storage.ImageFormatSniffer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图片识别（S16）：把一张图片变成「按阅读顺序排好的文字行」，交给规则引擎判读。
 *
 * 三条纪律：
 * - **不伪造**：未配置凭据时 503；识别不出文字时返回空行集合（上层判 EMPTY），不编文字；
 * - **预检在服务端**：大小、格式与**像素尺寸**按官方受理范围复核，不信任客户端声明；
 * - **原文不外泄**：异常只带错误码与原因，不把图片原文或整段响应写进日志。
 */
@Service
public class OcrService {

    private final OcrApi ocrApi;
    private final DitingProperties props;

    public OcrService(OcrApi ocrApi, DitingProperties props) {
        this.ocrApi = ocrApi;
        this.props = props;
    }

    /** 是否具备真实识别能力（桩实现只在测试环境注入） */
    public boolean isConfigured() {
        return !(ocrApi instanceof NotConfiguredOcrApi);
    }

    /**
     * 识别一张图片。
     *
     * @param image 图片二进制（≤ max-image-bytes，格式在允许列表内）
     * @return 按阅读顺序排好的文字行 + 官方全文；图上无文字时 lines 为空
     */
    public OcrParser.ParsedOcr recognize(byte[] image) {
        if (image == null || image.length == 0) {
            throw new BizException(ResultCode.UNPROCESSABLE, "图片内容为空");
        }
        long maxBytes = props.getMedia().getMaxImageBytes();
        if (image.length > maxBytes) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "图片超过大小上限（" + (maxBytes / 1024 / 1024) + "MB）");
        }
        String format = ImageFormatSniffer.sniff(image);
        List<String> allowed = props.getMedia().getAllowedImageFormats().stream()
            .map(ImageFormatSniffer::normalize)
            .toList();
        if (format == null || !allowed.contains(format)) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "图片格式不支持（支持 JPG/PNG/WebP/GIF/BMP/TIFF；手机 HEIC 需先转成 JPG）");
        }
        if (!isConfigured()) {
            throw new BizException(ResultCode.NOT_CONFIGURED, "图片识别（OCR）未配置");
        }
        // 尺寸预检（S16 补强，官方 RecognizeGeneral「重要提示」）：长宽 15–8192 像素、长宽比 <50。
        // 上传校验已经拦过一道，这里用**完整字节**再查一次：前缀里取不到尺寸的图（JPEG 的 SOFn 靠后、
        // 截断的 TIFF）只能在这里拦住，避免一次必然失败、还会按次数上限重试的付费调用。
        String sizeReason = ImageDimensionProbe.outOfOcrRange(ImageDimensionProbe.probe(image),
            props.getMedia().getMinImagePixels(), props.getMedia().getMaxImagePixels(),
            props.getMedia().getMaxImageAspectRatio());
        if (sizeReason != null) {
            throw new BizException(ResultCode.UNPROCESSABLE, sizeReason);
        }
        String data = ocrApi.recognize(image);
        OcrParser.ParsedOcr parsed = OcrParser.parse(data);
        return new OcrParser.ParsedOcr(parsed.lines(), parsed.content());
    }
}
