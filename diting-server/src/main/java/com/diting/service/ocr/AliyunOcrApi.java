package com.diting.service.ocr;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponseBody;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralRequest;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponse;
import com.aliyun.ocr_api20210707.models.RecognizeGeneralResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.diting.config.DitingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * 阿里云实现：默认走 **RecognizeAllText**（`ocr-api` 2021-07-07，统一识别，`Type=General`），
 * 可用 `diting.aliyun.ocr.api=general` 回退到旧的 RecognizeGeneral。
 *
 * 契约要点（官方文档核验日期 2026-09-27）：
 * - 两个接口都是 `Url` 与 `body` 二选一：这里固定走 **body（二进制）**——图片在私有对象存储里，
 *   交给云端拉 URL 会受桶策略与网络可达性限制；
 * - 单张 ≤10MB；图片格式 PNG/JPG/JPEG/BMP/GIF/TIFF/WebP（AllText 还支持 PDF，
 *   但产品只收图片：上传白名单里没有 PDF，工具链与导出也没按 PDF 做过）；
 * - 尺寸约束两个接口一致：长宽大于 15 且小于 8192 像素、长宽比小于 50（预检见 `OcrService`）；
 * - AllText 的 `Type` 是**必填单选**；`Type=General` 时官方参数表不支持 `OutputCoordinate`
 *   与 `AdvancedConfig`，因此不开坐标、不开成行/分段/表格等增强项（开不了的参数传了也没用，
 *   能开的（如 OutputStamp）会增加响应时间且对催收截图无意义）；
 * - 失败形态两条路径都要处理：响应体里带 `Code`（`Data` 为空）与 SDK 抛 `TeaException`，
 *   统一转成带官方错误码的 {@link OcrApiException}，由管线决定要不要重试。
 *
 * 返回给端口的仍然是「官方 `Data` 的 JSON」这一种形态：General 的 `Data` 本来就是 JSON 字符串，
 * AllText 的 `Data` 是类型化对象，这里序列化成 JSON。这样解析只在一个地方做（{@link OcrParser}），
 * 且解析器不依赖 SDK。
 */
public class AliyunOcrApi implements OcrApi {

    /** 统一识别（默认）：一条接口覆盖多类型，`Type` 指定图片类型 */
    public static final String API_ALL_TEXT = "alltext";
    /** 旧的通用文字识别（保留兼容开关，回滚时不用改代码） */
    public static final String API_GENERAL = "general";

    private static final String DEFAULT_ENDPOINT = "ocr-api.cn-hangzhou.aliyuncs.com";
    private static final String DEFAULT_IMAGE_TYPE = "General";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Client client;
    private final String api;
    private final String imageType;

    public AliyunOcrApi(DitingProperties props) throws Exception {
        DitingProperties.Aliyun aliyun = props.getAliyun();
        DitingProperties.Aliyun.Ocr ocr = aliyun.getOcr();
        String endpoint = ocr == null || ocr.getEndpoint() == null || ocr.getEndpoint().isBlank()
            ? DEFAULT_ENDPOINT
            : ocr.getEndpoint().trim();
        this.api = ocr == null || ocr.getApi() == null || ocr.getApi().isBlank()
            ? API_ALL_TEXT
            : ocr.getApi().trim().toLowerCase(Locale.ROOT);
        this.imageType = ocr == null || ocr.getImageType() == null || ocr.getImageType().isBlank()
            ? DEFAULT_IMAGE_TYPE
            : ocr.getImageType().trim();
        this.client = new Client(new Config()
            .setAccessKeyId(aliyun.getAccessKeyId())
            .setAccessKeySecret(aliyun.getAccessKeySecret())
            .setEndpoint(endpoint));
    }

    @Override
    public String recognize(byte[] image) {
        try {
            return API_GENERAL.equals(this.api) ? callGeneral(image) : callAllText(image);
        }
        catch (OcrApiException e) {
            throw e;
        }
        catch (TeaException e) {
            // SDK 把非 2xx 响应（含官方 Code/Message）包成 TeaException
            throw OcrApiException.ofCode(e.getCode(), e.getMessage());
        }
        catch (Exception e) {
            // 超时/网络中断：结果不确定，交给管线按次数上限重试
            throw OcrApiException.transientFailure("OCR 调用失败：" + e.getMessage(), e);
        }
    }

    /** 统一识别（RecognizeAllText）：Type 必填；通用文字识别用 General */
    private String callAllText(byte[] image) throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(image)) {
            RecognizeAllTextResponse response = client.recognizeAllText(
                new RecognizeAllTextRequest().setType(imageType).setBody(in));
            RecognizeAllTextResponseBody body = response == null ? null : response.getBody();
            if (body == null) {
                throw OcrApiException.transientFailure("OCR 未返回响应体", null);
            }
            return requireAllTextData(body.getCode(), body.getData(), body.getMessage());
        }
    }

    /** 旧的通用文字识别（RecognizeGeneral）：`Data` 是内嵌 JSON 的字符串 */
    private String callGeneral(byte[] image) throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(image)) {
            RecognizeGeneralResponse response = client.recognizeGeneral(
                new RecognizeGeneralRequest().setBody(in));
            RecognizeGeneralResponseBody body = response == null ? null : response.getBody();
            if (body == null) {
                throw OcrApiException.transientFailure("OCR 未返回响应体", null);
            }
            return requireData(body.getCode(), body.getData(), body.getMessage());
        }
    }

    /**
     * 成功时 `Data` 有值；失败时官方只给 `Code`/`Message`。
     * 抽成静态方法是为了单测能直接验「错误码不被吞掉」，不必起真实客户端。
     *
     * @throws OcrApiException 云端给了错误码（按错误码表分类）或什么都没给（不确定，可重试）
     */
    static String requireData(String code, String data, String cloudMessage) {
        if (data != null && !data.isBlank()) {
            return data;
        }
        return failWith(code, cloudMessage);
    }

    /**
     * RecognizeAllText 的 `Data` 是对象（SDK 已给出类型化模型）：序列化成 JSON 交给 {@link OcrParser}。
     * 空对象也要放过——图上没有文字时官方仍返回 `Data`（`Content` 为空），那是「EMPTY」而不是错误。
     */
    static String requireAllTextData(String code, Object data, String cloudMessage) {
        if (data != null) {
            try {
                return MAPPER.writeValueAsString(data);
            }
            catch (Exception e) {
                throw OcrApiException.transientFailure("OCR 结果无法序列化", e);
            }
        }
        return failWith(code, cloudMessage);
    }

    private static String failWith(String code, String cloudMessage) {
        if (code != null && !code.isBlank()) {
            throw OcrApiException.ofCode(code, cloudMessage);
        }
        throw OcrApiException.transientFailure("OCR 未返回识别结果（无错误码）", null);
    }
}
