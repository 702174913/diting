package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.Agreement;
import com.diting.mapper.AgreementConsentMapper;
import com.diting.mapper.AgreementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S19 用户协议：同意校验、HTML 清洗、保存版本递增。
 * 纯内存单测，不连库。
 */
class AgreementServiceTest {

    private AgreementMapper agreementMapper;
    private AgreementConsentMapper consentMapper;
    private AgreementService service;

    private Agreement row(String key, int version) {
        Agreement agreement = new Agreement();
        agreement.setId(1L);
        agreement.setAgreementKey(key);
        agreement.setTitle("t");
        agreement.setContentHtml("<p>x</p>");
        agreement.setVersion(version);
        agreement.setSortOrder(0);
        return agreement;
    }

    @BeforeEach
    void setup() {
        agreementMapper = mock(AgreementMapper.class);
        consentMapper = mock(AgreementConsentMapper.class);
        when(agreementMapper.selectList(any())).thenReturn(List.of(
            row("service_terms", 1), row("privacy_guide", 2),
            row("personal_info_auth", 1), row("recording_auth", 3)));
        service = new AgreementService(agreementMapper, consentMapper);
    }

    @Test
    @DisplayName("未勾选注册一律拒绝")
    void unagreedRejected() {
        assertThatThrownBy(() -> service.assertConsented(false, Map.of()))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
    }

    @Test
    @DisplayName("版本对不上（admin 刚更新过）时 409，请用户重看")
    void staleVersionsRejected() {
        assertThatThrownBy(() -> service.assertConsented(true,
            Map.of("service_terms", 1, "privacy_guide", 1,
                "personal_info_auth", 1, "recording_auth", 3)))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    @DisplayName("勾选且版本一致时放行")
    void matchingVersionsPass() {
        service.assertConsented(true,
            Map.of("service_terms", 1, "privacy_guide", 2,
                "personal_info_auth", 1, "recording_auth", 3));
    }

    @Test
    void revisionChangedBeforeConsentWriteAbortsAccountTransactionWithoutPartialConsent() {
        Map<String, Integer> seen = Map.of("service_terms", 1, "privacy_guide", 2,
            "personal_info_auth", 1, "recording_auth", 3);
        service.assertConsented(true, seen);
        when(agreementMapper.selectList(any())).thenReturn(List.of(
            row("service_terms", 2), row("privacy_guide", 2),
            row("personal_info_auth", 1), row("recording_auth", 3)));
        assertThatThrownBy(() -> service.recordConsents(7L, "127.0.0.1", seen))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode()).isEqualTo(ResultCode.CONFLICT);
        verify(consentMapper, never()).insert(any(com.diting.entity.AgreementConsent.class));
    }

    @Test
    @DisplayName("清洗掉 script/事件属性/javascript 链接，保留排版标签")
    void sanitizeStripsActiveContent() {
        String dirty = "<p onclick=\"evil()\">hi</p><script>alert(1)</script>"
            + "<a href=\"javascript:evil()\">x</a><b>ok</b>";
        String clean = AgreementService.sanitize(dirty);
        assertThat(clean).doesNotContain("<script").doesNotContain("onclick")
            .doesNotContain("javascript:");
        assertThat(clean).contains("<p>hi</p>").contains("<b>ok</b>");
    }

    @Test
    void whitelistRejectsMalformedSvgUnquotedProtocolsAndEncodedEventAttrs() {
        String dirty = "<svg/onload=alert(1)><a href=javascript:alert(1)>x</a>"
            + "<img src=x onerror=alert(2)><a href='jav&#x61;script:alert(3)'>y</a>"
            + "<p style='background:url(javascript:evil())' oNclick='evil()'>正文</p>"
            + "<table><tr><td>条款</td></tr></table>";
        String clean = AgreementService.sanitize(dirty);
        assertThat(clean).doesNotContain("<svg", "<img", "onload", "onerror", "onclick",
                "javascript:", "style=", "src=");
        assertThat(clean).contains("<p>正文</p>", "<table>", "<td>条款</td>");
    }

    @Test
    void legacyDirtyRowsAreCleanedOnReadWithoutMutatingMapperEntity() {
        Agreement legacy = row("service_terms", 4);
        legacy.setContentHtml("<p onclick=evil()>正文</p><script>alert(1)</script>");
        when(agreementMapper.selectOne(any())).thenReturn(legacy);
        Agreement safe = service.detail("service_terms");
        assertThat(safe.getContentHtml()).isEqualTo("<p>正文</p>");
        assertThat(legacy.getContentHtml()).contains("onclick");
    }

    @Test
    @DisplayName("内容无变化时不 bump 版本、不写库")
    void saveWithoutChangesSkipsWrite() {
        Agreement existing = row("service_terms", 5);
        existing.setTitle("服务协议");
        existing.setContentHtml("<p>正文</p>");
        when(agreementMapper.selectOne(any())).thenReturn(existing);

        Agreement saved = service.save("service_terms", "服务协议", "<p>正文</p>");

        assertThat(saved.getVersion()).isEqualTo(5);
        verify(agreementMapper, never()).updateById(any(Agreement.class));
    }

    @Test
    @DisplayName("内容变化时 version +1")
    void saveWithChangesBumpsVersion() {
        Agreement existing = row("service_terms", 5);
        existing.setTitle("服务协议");
        existing.setContentHtml("<p>旧</p>");
        when(agreementMapper.selectOne(any())).thenReturn(existing);

        Agreement saved = service.save("service_terms", "服务协议", "<p>新</p>");

        assertThat(saved.getVersion()).isEqualTo(6);
        verify(agreementMapper).updateById(any(Agreement.class));
    }
}
