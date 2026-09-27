package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.BusinessTime;
import com.diting.common.ResultCode;
import com.diting.entity.Agreement;
import com.diting.entity.AgreementConsent;
import com.diting.mapper.AgreementConsentMapper;
import com.diting.mapper.AgreementMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用户协议（S19）。
 *
 * - 4 个固定 key，顺序即展示顺序；
 * - admin 保存：标题 + 正文，version +1（内容比对无变化时不 bump，避免版本号空转）；
 * - 注册：要求 agreed=true 且客户端看到的版本与当前一致，否则 409 请重新确认；
 *   通过后把 4 份当前版本快照进 agreement_consents。
 */
@Service
public class AgreementService {

    public static final List<String> ORDERED_KEYS = List.of(
        "service_terms", "privacy_guide", "personal_info_auth", "recording_auth");

    private static final Safelist HTML_ALLOWLIST = new Safelist()
        .addTags("a", "p", "div", "span", "br", "b", "strong", "i", "em", "u", "s",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li",
            "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
            "hr", "pre", "small", "sup", "sub")
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https");

    private final AgreementMapper agreementMapper;
    private final AgreementConsentMapper consentMapper;

    public AgreementService(AgreementMapper agreementMapper, AgreementConsentMapper consentMapper) {
        this.agreementMapper = agreementMapper;
        this.consentMapper = consentMapper;
    }

    /** 当前 4 份协议（按展示顺序；缺 seed 时抛 500 而不是返回半份） */
    public List<Agreement> current() {
        List<Agreement> rows = agreementMapper.selectList(new LambdaQueryWrapper<Agreement>()
            .orderByAsc(Agreement::getSortOrder).orderByAsc(Agreement::getId));
        if (rows.size() != ORDERED_KEYS.size()) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "协议数据缺失，请联系管理员");
        }
        return rows.stream().map(AgreementService::safeView).toList();
    }

    /** 当前版本号快照：key -> version（注册页展示 + 提交校验用） */
    public Map<String, Integer> currentVersions() {
        return current().stream().collect(java.util.stream.Collectors.toMap(
            Agreement::getAgreementKey, Agreement::getVersion));
    }

    public Agreement detail(String key) {
        Agreement row = agreementMapper.selectOne(new LambdaQueryWrapper<Agreement>()
            .eq(Agreement::getAgreementKey, key));
        if (row == null) {
            throw new BizException(ResultCode.NOT_FOUND, "协议不存在");
        }
        return safeView(row);
    }

    /** admin 保存：标题/正文更新 + version +1；内容无变化时直接返回当前行 */
    @Transactional
    public Agreement save(String key, String title, String contentHtml) {
        if (title == null || title.isBlank()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "协议标题不能为空");
        }
        if (contentHtml == null || contentHtml.isBlank()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "协议正文不能为空");
        }
        // 富文本只允许排版标签：去事件属性与脚本，防存储型 XSS（详情页是 rich-text 原样渲染）
        String safeHtml = sanitize(contentHtml);
        Agreement row = detail(key);
        if (row.getTitle().equals(title.strip()) && row.getContentHtml().equals(safeHtml)) {
            return row;
        }
        row.setTitle(title.strip());
        row.setContentHtml(safeHtml);
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedAt(BusinessTime.now());
        agreementMapper.updateById(row);
        return row;
    }

    /**
     * 注册同意校验：必须勾选，且客户端看到的 4 份版本与当前一致。
     * 版本对不上说明 admin 刚更新过协议——让用户重新看一遍再勾，而不是静默同意旧版。
     */
    public void assertConsented(boolean agreed, Map<String, Integer> seenVersions) {
        if (!agreed) {
            throw new BizException(ResultCode.UNPROCESSABLE, "请先阅读并同意用户协议");
        }
        Map<String, Integer> current = currentVersions();
        if (seenVersions == null || !seenVersions.equals(current)) {
            throw new BizException(ResultCode.CONFLICT, "协议已更新，请重新阅读并确认");
        }
    }

    /** 注册成功后记录同意快照（审计用） */
    public void recordConsents(Long userId, String ip, Map<String, Integer> seenVersions) {
        List<Agreement> agreements = current();
        Map<String, Integer> actual = agreements.stream().collect(java.util.stream.Collectors.toMap(
            Agreement::getAgreementKey, Agreement::getVersion));
        if (seenVersions == null || !seenVersions.equals(actual)) {
            throw new BizException(ResultCode.CONFLICT, "协议已更新，请重新阅读并确认");
        }
        var now = BusinessTime.now();
        for (Agreement agreement : agreements) {
            AgreementConsent consent = new AgreementConsent();
            consent.setUserId(userId);
            consent.setAgreementKey(agreement.getAgreementKey());
            consent.setVersion(agreement.getVersion());
            consent.setAgreedAt(now);
            consent.setIp(ip);
            consentMapper.insert(consent);
        }
    }

    /** 白名单同时用于写入和读取：旧库正文未经清洗时也绝不直接出站。 */
    static String sanitize(String html) {
        String out = Jsoup.clean(html, "", HTML_ALLOWLIST,
            new Document.OutputSettings().prettyPrint(false));
        if (Jsoup.parseBodyFragment(out).text().isBlank()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "协议正文不能为空");
        }
        return out;
    }

    private static Agreement safeView(Agreement row) {
        Agreement safe = new Agreement();
        org.springframework.beans.BeanUtils.copyProperties(row, safe);
        safe.setContentHtml(sanitize(row.getContentHtml()));
        return safe;
    }
}
