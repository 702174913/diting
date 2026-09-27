package com.diting.controller;

import com.diting.common.R;
import com.diting.entity.Agreement;
import com.diting.service.AgreementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户协议公开接口（S19）：注册页展示用，无需登录（见 WebConfig 白名单）。
 * 列表只给标题 + 版本（省流量）；正文按 key 取。
 */
@RestController
@RequestMapping("/api/agreements")
public class AgreementController {

    private final AgreementService agreementService;

    public AgreementController(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(agreementService.current().stream()
            .map(a -> Map.<String, Object>of(
                "key", a.getAgreementKey(),
                "title", a.getTitle(),
                "version", a.getVersion()))
            .toList());
    }

    @GetMapping("/versions")
    public R<Map<String, Integer>> versions() {
        return R.ok(agreementService.currentVersions());
    }

    @GetMapping("/{key}")
    public R<Map<String, Object>> detail(@PathVariable String key) {
        Agreement agreement = agreementService.detail(key);
        return R.ok(Map.of(
            "key", agreement.getAgreementKey(),
            "title", agreement.getTitle(),
            "version", agreement.getVersion(),
            "contentHtml", agreement.getContentHtml()));
    }
}
