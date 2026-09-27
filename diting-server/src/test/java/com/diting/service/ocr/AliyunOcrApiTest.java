package com.diting.service.ocr;

import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一识别（RecognizeAllText）适配层的契约测试：**不打网络**，但把「SDK 类型化模型 → 端口返回的 JSON →
 * 解析器读出的行」这条链子串起来验一遍。
 *
 * 为什么必须这样测：端口的约定是「返回官方 `Data` 的 JSON」，而 AllText 的 `Data` 是对象（General 是字符串），
 * 中间多了一次序列化。序列化出来的字段名（camelCase）与官方报文（PascalCase）不同，
 * 只测解析器或只测适配器都可能漏掉这个错位。
 */
class AliyunOcrApiTest {

    private static RecognizeAllTextResponseBody.RecognizeAllTextResponseBodyData stubData() {
        return new RecognizeAllTextResponseBody.RecognizeAllTextResponseBodyData()
            .setContent("我保证能减免利息。\n请你今天把款还上。")
            .setHeight(200)
            .setWidth(400)
            .setSubImageCount(1)
            .setSubImages(List.of(new RecognizeAllTextResponseBody
                .RecognizeAllTextResponseBodyDataSubImages()
                .setSubImageId(0)
                .setType("general")
                .setAngle(0)
                .setBlockInfo(new RecognizeAllTextResponseBody
                    .RecognizeAllTextResponseBodyDataSubImagesBlockInfo()
                    .setBlockCount(2)
                    .setBlockDetails(List.of(
                        new RecognizeAllTextResponseBody
                            .RecognizeAllTextResponseBodyDataSubImagesBlockInfoBlockDetails()
                            .setBlockId(0)
                            .setBlockContent("我保证能减免利息。")
                            .setBlockConfidence(98),
                        new RecognizeAllTextResponseBody
                            .RecognizeAllTextResponseBodyDataSubImagesBlockInfoBlockDetails()
                            .setBlockId(1)
                            .setBlockContent("请你今天把款还上。")
                            .setBlockConfidence(97))))));
    }

    @Test
    @DisplayName("SDK 的 Data 模型序列化后能被解析器读出与原接口一致的两行")
    void allTextModelSerializesIntoParsableJson() {
        String json = AliyunOcrApi.requireAllTextData(null, stubData(), null);

        OcrParser.ParsedOcr parsed = OcrParser.parse(json);

        assertThat(parsed.lines()).containsExactly("我保证能减免利息。", "请你今天把款还上。");
        // 换接口不改变判读口径：与旧接口桩数据解析出的行完全一致
        assertThat(parsed.lines()).isEqualTo(OcrParser.parse(StubOcrApi.STUB_LEGACY_DATA).lines());
        assertThat(parsed.content()).contains("减免利息");
    }

    @Test
    @DisplayName("云端给了错误码 → 带码失败（不吞成 INTERNAL_ERROR），网络类失败无码可重试")
    void allTextFailuresKeepCloudCode() {
        assertThatThrownBy(() -> AliyunOcrApi.requireAllTextData("ocrServiceNotOpen", null,
            "You have not activated the OCR service."))
            .isInstanceOf(OcrApiException.class)
            .satisfies(e -> {
                assertThat(((OcrApiException) e).getCode()).isEqualTo("ocrServiceNotOpen");
                assertThat(((OcrApiException) e).isRetryable()).isFalse();
            });

        assertThatThrownBy(() -> AliyunOcrApi.requireAllTextData(null, null, null))
            .isInstanceOf(OcrApiException.class)
            .satisfies(e -> assertThat(((OcrApiException) e).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("空 Data（图上没文字）不算失败：交给解析器判 EMPTY，重试没有意义")
    void emptyDataIsNotAFailure() {
        String json = AliyunOcrApi.requireAllTextData(null,
            new RecognizeAllTextResponseBody.RecognizeAllTextResponseBodyData().setContent(""), null);

        assertThat(OcrParser.parse(json).lines()).isEmpty();
    }
}
