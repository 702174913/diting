package com.diting.controller;

import com.diting.dto.ReportDetailResponse;
import com.diting.dto.SentenceResponse;
import com.diting.dto.ViolationResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRecordingRedactionTest {
    @Test void operatorViewRedactsEveryNarrativeField() throws Exception {
        String phone = "13812345678";
        SentenceResponse sentence = new SentenceResponse(1L, 1, null, 1, "AGENT", 0L, 100L,
            phone, null, List.of(), List.of(), List.of("联系 " + phone));
        ViolationResponse violation = new ViolationResponse(2L, 3L, "规则 " + phone, "hit", "ALL",
            1L, null, List.of(), List.of(), 10, "命中 " + phone,
            List.of(new com.diting.dto.LegalClauseResponse(7L, "个人信息保护法", "第十三条",
                "条款原文", null, true, null, null)));
        ReportDetailResponse detail = new ReportDetailResponse(1L, "remote", "DT-1", "COMPLETED",
            1, "now", 2L, 1, "PUBLISHED", "PUBLISHED", 0L, List.of(), false,
            1, 1, 1, 10, 90, 80, false, "now", false, false,
            List.of("总览 " + phone), List.of(sentence), List.of(violation));

        Method redact = AdminRecordingController.class.getDeclaredMethod("redact", ReportDetailResponse.class);
        redact.setAccessible(true);
        ReportDetailResponse masked = (ReportDetailResponse) redact.invoke(null, detail);

        assertThat(masked.redacted()).isTrue();
        assertThat(masked.notes().get(0)).doesNotContain(phone);
        assertThat(masked.sentences().get(0).text()).doesNotContain(phone).hasSameSizeAs(phone);
        assertThat(masked.sentences().get(0).notes().get(0)).doesNotContain(phone);
        assertThat(masked.violations().get(0).ruleName()).doesNotContain(phone);
        assertThat(masked.violations().get(0).note()).doesNotContain(phone);
        // 法律条款是公开文本、非叙述性字段，脱敏原样透传
        assertThat(masked.violations().get(0).legalClauses()).hasSize(1);
        assertThat(masked.violations().get(0).legalClauses().get(0).lawName()).isEqualTo("个人信息保护法");
    }
}
