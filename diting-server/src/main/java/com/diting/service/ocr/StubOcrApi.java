package com.diting.service.ocr;

/**
 * 桩实现：**仅测试环境**使用（diting.aliyun.ocr.type=stub）。
 *
 * 返回与官方**报文结构一致**的确定性结果（含一行会命中规则的话术），
 * 让「图片 → OCR → 文字 → 规则判读 → 报告」整条链路在没有云凭据时也能被验收。
 * 绝不用于生产：生产缺凭据时走 {@link NotConfiguredOcrApi}（503）。
 *
 * 两份数据对应两个接口，解析结果必须**一模一样的两行**（有单测锁住）：
 * - {@link #STUB_DATA}：统一识别 RecognizeAllText（当前默认，`Type=General`），
 *   官方 HTTP 报文用 PascalCase —— 这也是模拟真实报文的形态；
 * - {@link #STUB_LEGACY_DATA}：通用文字识别 RecognizeGeneral（`api=general` 回滚开关）。
 */
public class StubOcrApi implements OcrApi {

    /** RecognizeAllText（Type=General）的 `Data`：Content 全文 + 文字块（General 不返回坐标） */
    public static final String STUB_DATA = """
        {"Content":"我保证能减免利息。\\n请你今天把款还上。",
         "SubImageCount":1,
         "SubImages":[{"SubImageId":0,"Type":"general","Angle":0,
           "BlockInfo":{"BlockCount":2,"BlockDetails":[
             {"BlockId":0,"BlockContent":"我保证能减免利息。","BlockConfidence":98},
             {"BlockId":1,"BlockContent":"请你今天把款还上。","BlockConfidence":97}]}}],
         "Height":200,"Width":400}
        """;

    /** RecognizeGeneral 的 `Data`：内嵌 JSON 字符串（content + prism_wordsInfo 词块与坐标） */
    public static final String STUB_LEGACY_DATA = """
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
