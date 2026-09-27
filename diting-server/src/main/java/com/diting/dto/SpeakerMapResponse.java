package com.diting.dto;

import com.diting.service.report.ReportService;

/** 角色映射保存结果：新映射版本 + 本次分析产生的报告版本与状态 */
public record SpeakerMapResponse(long mappingRevision, long reportVersion, String analysisStatus) {

    public static SpeakerMapResponse of(ReportService.SpeakerMapOutcome outcome) {
        return new SpeakerMapResponse(outcome.mappingRevision(), outcome.reportVersion(),
            outcome.analysisStatus());
    }
}
