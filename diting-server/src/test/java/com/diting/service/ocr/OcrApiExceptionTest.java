package com.diting.service.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 云端失败的分类与错误码保留（S16 补强，官方错误码表核验日期 2026-09-27）。
 *
 * 这里锁三件事：
 * 1. 官方错误码**不被吞掉**（以前一律变成常量 INTERNAL_ERROR，线上分不清「没开通」和「图坏了」）；
 * 2. 「未开通/无权限/图片不合法」这类重试无用的错误当次判死，不再重复付费；
 * 3. 限流/云端 5xx/网络中断仍然可重试，未知错误码偏保守（可重试）。
 */
class OcrApiExceptionTest {

    @Test
    @DisplayName("云端给了错误码：保留原码，并按错误码表判「重试无用」")
    void permanentCloudCodesAreNotRetried() {
        for (String code : new String[]{"ocrServiceNotOpen", "noPermission", "OcrServiceExpired",
            "unsupportedImageFormat", "illegalImageSize", "exceededImageContent"}) {
            OcrApiException e = OcrApiException.ofCode(code, "官方提示");
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.isRetryable()).as(code).isFalse();
            assertThat(e.errorMark()).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("限流与云端 5xx 可重试（退避后仍可能成功）")
    void throttlingAndServerErrorsAreRetryable() {
        for (String code : new String[]{"Throttling", "Throttling.User", "algorithmError",
            "ServiceUnavailable", "ServiceTimeout", "AlgorithmTimeout", "imageTimeOut"}) {
            assertThat(OcrApiException.ofCode(code, null).isRetryable()).as(code).isTrue();
        }
    }

    @Test
    @DisplayName("未知错误码按可重试处理：多花几次调用，好过把用户任务误判死")
    void unknownCodeStaysRetryable() {
        assertThat(OcrApiException.ofCode("SomeNewCode2027", null).isRetryable()).isTrue();
        assertThat(OcrApiException.isRetryableCode(null)).isTrue();
    }

    @Test
    @DisplayName("网络中断/超时：无错误码，标记为不确定，可重试")
    void transientFailureHasNoCode() {
        OcrApiException e = OcrApiException.transientFailure("OCR 调用失败：timeout", new RuntimeException("boom"));
        assertThat(e.getCode()).isNull();
        assertThat(e.isRetryable()).isTrue();
        assertThat(e.errorMark()).isEqualTo("OCR_UNCERTAIN");
    }

    @Test
    @DisplayName("响应体里有 Data 就用 Data；Data 空 + Code 有值 → 带码失败（官方失败形态）")
    void requireDataKeepsCloudCode() {
        assertThat(AliyunOcrApi.requireData(null, "{\"content\":\"文字\"}", null))
            .isEqualTo("{\"content\":\"文字\"}");

        assertThatThrownBy(() -> AliyunOcrApi.requireData("ocrServiceNotOpen", null,
            "You have not activated the OCR service."))
            .isInstanceOf(OcrApiException.class)
            .hasMessageContaining("ocrServiceNotOpen")
            .satisfies(e -> assertThat(((OcrApiException) e).isRetryable()).isFalse());

        // 既没 Data 也没 Code：结果不确定，可重试
        assertThatThrownBy(() -> AliyunOcrApi.requireData(null, "  ", null))
            .isInstanceOf(OcrApiException.class)
            .satisfies(e -> assertThat(((OcrApiException) e).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("错误码与云侧文案会被截断，不把整段响应写进日志与数据库")
    void longCloudMessageIsClipped() {
        String longCode = "X".repeat(500);
        assertThat(OcrApiException.ofCode(longCode, null).errorMark()).hasSize(120);
    }
}
