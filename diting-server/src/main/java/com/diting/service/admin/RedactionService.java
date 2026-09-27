package com.diting.service.admin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本脱敏（开发文档 §9）。
 *
 * 关键设计：掩码与原串**等长**（逐字符替换），因此脱敏后所有高亮区间依然成立——
 * 否则 OPERATOR 看到的报告会出现「高亮切错字符」。这是文档里明确点出的偏移风险。
 *
 * 只对**高置信度**的模式脱敏（手机号、身份证、邮箱、工号、银行卡、地址）。
 * 姓名不做猜测式脱敏：没有名单就猜名字，会把普通词汇也涂掉，反而降低报告可用性
 * （这一点在上线前需要与业务确认名单来源，见 S10 记录）。
 *
 * 地址口径（路号级）：省 / 市 / 区县镇乡保留可读，从路街巷及门牌详情开始涂；
 * 没有省市前缀的裸地址（“建国路 88 号”“3 栋 2 单元 501”）整段涂，避免漏脱敏。
 */
public final class RedactionService {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern EMPLOYEE_ID = Pattern.compile("(工号|员工编号|工牌)[:：]?\\s*[A-Za-z0-9-]{3,20}");
    /**
     * 地址（路号级口径）。三个分支：
     * 1) 带省市前缀的完整地址：省 / 市 / 区县镇乡保留，从路街巷及门牌详情开始涂；
     * 2) 裸路名 + 门牌（“建国路 88 号”）：整段涂；
     * 3) 裸楼栋单元室（“3 栋 2 单元 501 室”）：整段涂。“室温”之类的词用 (?!温) 排除。
     * 统一用“非断句字符”收尾，避免把半句话涂掉，也避免跨标点吞掉后文。
     */
    private static final String NO_BREAK = "[^，。；：、？！\\s]";
    private static final Pattern ADDRESS_FULL = Pattern.compile(
        NO_BREAK + "{1,8}(?:省|自治区|自治州|盟)" + NO_BREAK + "{0,12}(?:市|区|县|旗|镇|乡)"
            + NO_BREAK + "{0,18}(?:路|街|大道|巷|弄|胡同|里|村)" + NO_BREAK + "{0,14}(?:号|栋|幢|单元|室|楼|层)?"
            + "|" + NO_BREAK + "{1,12}(?:市|区|县|旗|镇|乡)" + NO_BREAK + "{0,18}(?:路|街|大道|巷|弄|胡同|里|村)"
            + NO_BREAK + "{0,14}(?:号|栋|幢|单元|室|楼|层)?"
            + "|" + NO_BREAK + "{1,12}(?:市|区|县|旗|镇|乡)\\s*\\d{1,6}\\s*(?:号|栋|幢|单元|室|楼|层)");
    private static final Pattern ADDRESS_BARE_ROAD = Pattern.compile(
        "(?![到在去来从往向住])[\\u4e00-\\u9fa5&&[^市区县镇乡旗省]]{2,6}(?:路|街|大道|巷|弄|胡同|里)\\s*\\d{1,6}\\s*(?:号|栋|幢|单元|楼|层|室)?");
    private static final Pattern ADDRESS_BARE_UNIT = Pattern.compile(
        "\\d{1,4}\\s*(?:栋|幢)\\s*\\d{0,4}\\s*(?:单元)?\\s*\\d{0,4}\\s*室(?!温)"
            + "|\\d{1,4}\\s*单元\\s*\\d{1,4}\\s*室(?!温)");
    /** 从路街巷及门牌详情开始涂：保留省市等粗粒度，细粒度全部掩码 */
    private static final Pattern ADDRESS_DETAIL_START = Pattern.compile(
        "(?:路|街|大道|巷|弄|胡同|里|村)|\\d(?=\\s*\\d*\\s*(?:号|栋|幢|单元|室|楼|层))");

    private RedactionService() {
    }

    /** 返回与入参等长的脱敏文本 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = maskAll(BANK_CARD, text, 4, 4);
        masked = maskAll(ID_CARD, masked, 4, 4);
        masked = maskAll(PHONE, masked, 3, 4);
        masked = maskEmail(masked);
        masked = maskEmployeeId(masked);
        masked = maskAddress(masked);
        return masked;
    }

    /** 是否真的发生了脱敏（用于给 OPERATOR 标注） */
    public static boolean changed(String original, String redacted) {
        return original != null && !original.equals(redacted);
    }

    private static String maskAll(Pattern pattern, String text, int keepHead, int keepTail) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            for (int i = start + keepHead; i < end - keepTail; i++) {
                out.setCharAt(i, '*');
            }
        }
        return out.toString();
    }

    private static String maskEmail(String text) {
        Matcher matcher = EMAIL.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (matcher.find()) {
            int at = text.indexOf('@', matcher.start());
            for (int i = matcher.start() + 2; i < at; i++) {
                out.setCharAt(i, '*');
            }
        }
        return out.toString();
    }

    private static String maskEmployeeId(String text) {
        Matcher matcher = EMPLOYEE_ID.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (matcher.find()) {
            // 只涂工号数字部分，保留“工号”这个说明，便于审阅者理解为什么被涂
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (Character.isDigit(text.charAt(i))) {
                    out.setCharAt(i, '*');
                }
            }
        }
        return out.toString();
    }

    /**
     * 地址脱敏（路号级口径，等长掩码）：
     * - 带省市前缀：保留到市 / 区县镇乡为止，从路街巷及门牌详情开始涂；
     * - 裸路名 + 门牌、裸楼栋单元室：整段涂。
     */
    private static String maskAddress(String text) {
        StringBuilder out = new StringBuilder(text);
        maskAddressFull(ADDRESS_FULL, text, out);
        maskAddressWhole(ADDRESS_BARE_ROAD, text, out);
        maskAddressWhole(ADDRESS_BARE_UNIT, text, out);
        return out.toString();
    }

    private static void maskAddressFull(Pattern pattern, String text, StringBuilder out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            Matcher detail = ADDRESS_DETAIL_START.matcher(matcher.group());
            int maskFrom = matcher.end();
            if (detail.find()) {
                maskFrom = matcher.start() + detail.start();
            }
            for (int i = maskFrom; i < matcher.end(); i++) {
                out.setCharAt(i, '*');
            }
        }
    }

    private static void maskAddressWhole(Pattern pattern, String text, StringBuilder out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                out.setCharAt(i, '*');
            }
        }
    }
}
