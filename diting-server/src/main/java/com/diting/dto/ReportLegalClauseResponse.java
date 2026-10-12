package com.diting.dto;

/** 报告公开的法律依据：不包含后台内部备注及管理元数据。 */
public record ReportLegalClauseResponse(
    Long id,
    String lawName,
    String articleNo,
    String content
) {
    public static ReportLegalClauseResponse from(LegalClauseResponse clause) {
        return new ReportLegalClauseResponse(clause.id(), clause.lawName(), clause.articleNo(), clause.content());
    }
}
