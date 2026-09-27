package com.diting.service.ocr;

/**
 * 桩实现：**仅测试环境**使用（diting.aliyun.ocr.type=stub）。
 *
 * 返回与官方结构一致的确定性结果（含一行会命中规则的话术），
 * 让「图片 → OCR → 文字 → 规则判读 → 报告」整条链路在没有云凭据时也能被验收。
 * 绝不用于生产：生产缺凭据时走 {@link NotConfiguredOcrApi}（503）。
 */
public class StubOcrApi implements OcrApi {

    /** 与官方 Data 字段同构：content + prism_wordsInfo（含坐标与高度） */
    public static final String STUB_DATA = """
        {"content":"我保证能减免利息。\\n请你今天把款还上。",
         "prism_wordsInfo":[
           {"word":"我保证能减免利息。","pos":[{"x":20,"y":30}],"height":24,"width":200},
           {"word":"请你今天把款还上。","pos":[{"x":20,"y":70}],"height":24,"width":180}],
         "prism_wnum":2,"height":200,"width":400}
        """;

    @Override
    public String recognize(byte[] image) {
        return STUB_DATA;
    }
}
