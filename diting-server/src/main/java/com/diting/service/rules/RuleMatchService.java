package com.diting.service.rules;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯函数判读引擎（开发文档 §8）。
 *
 * 只做三件事：规范化匹配、产出证据与原文高亮区间、按去重口径算分。
 * 不访问数据库、不调用任何模型、不生成 HTML —— 因此可以用固定输入反复复算（RULE-10）。
 *
 * 计分口径：`riskScore = min(100, Σ 每个命中规则的扣分)`，同一条规则在一通电话里**最多扣一次**，
 * 但**保留全部证据**；`complianceScore = 100 - riskScore`；`riskScore >= 阈值` 视为风险。
 *
 * 角色限定：见 {@link #ROLE_TARGETING_HIDDEN} —— 目标角色功能当前隐藏，角色未确认时角色限定
 * 一律按「任意角色」判读（不区分说话人）；角色已确认或开关改回 false 时，回到 §8 原设计
 * （未确认角色时角色限定规则跳过并给出说明）。
 */
@Service
public final class RuleMatchService {

    /**
     * 目标角色功能隐藏开关。
     *
     * 开启时：角色**未确认**就按「任意角色」判读，不再把角色限定规则放进 skipped——
     * 等价于不区分说话人。之所以这么定：角色确认链路（`ReportService.saveMapping`）目前没有 HTTP 入口，
     * 角色永远处于未确认状态，而当前规则集 31 条里有 26 条 `role_scope=AGENT`，
     * 按原口径会让它们全部静默失效（实测任务 1046：riskScore=0 却发布为「合规」）。
     * 角色已确认时，角色限定照旧生效（原口径不变）。
     *
     * 后期重新启用：改回 false，并恢复角色确认入口（后台 `ROLE_TARGETING_ENABLED` 同步打开）。
     */
    public static final boolean ROLE_TARGETING_HIDDEN = true;

    /** SpeakerId 不能推断业务身份；隐藏开关关闭时，角色依赖规则不参与判读。 */
    public static final String REASON_ROLE_UNCONFIRMED = "无法仅凭 SpeakerId 判断业务角色，规则未参与判读";

    /** 角色范围通配 */
    private static final String ANY = "ANY";

    public RuleModels.AnalysisResult analyze(List<RuleModels.SentenceView> sentences,
                                             Map<Integer, String> speakerRoles,
                                             boolean rolesConfirmed,
                                             List<RuleModels.RuleDefinition> rules) {
        List<RuleModels.SentenceView> ordered = new ArrayList<>(sentences == null ? List.of() : sentences);
        ordered.sort(Comparator.comparingInt(RuleModels.SentenceView::sequence));

        List<RuleModels.ViolationItem> violations = new ArrayList<>();
        List<RuleModels.SkippedRule> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // 隐藏态 + 角色未确认：不设角色门槛，等价于每条规则都是「任意角色」；
        // 角色已确认时（无论开关）以及开关关闭时，角色限定都照旧生效（§8 原口径，语义测试因此仍然成立）
        boolean ignoreRoles = ROLE_TARGETING_HIDDEN && !rolesConfirmed;
        for (RuleModels.RuleDefinition rule : rules == null ? List.<RuleModels.RuleDefinition>of() : rules) {
            if (!ROLE_TARGETING_HIDDEN && rule.roleDependent() && !rolesConfirmed) {
                skipped.add(new RuleModels.SkippedRule(rule.ruleId(), rule.name(), REASON_ROLE_UNCONFIRMED));
                continue;
            }
            switch (rule.matchType()) {
                case HIT -> collectHits(rule, ordered, speakerRoles, violations, ignoreRoles);
                case REQUIRE -> collectRequire(rule, ordered, speakerRoles, violations, notes, ignoreRoles);
                case MUST_HAVE -> collectMustHave(rule, ordered, speakerRoles, violations, notes, ignoreRoles);
            }
        }

        if (!skipped.isEmpty()) {
            notes.add("有 " + skipped.size() + " 条业务角色限定规则因无法从 SpeakerId 判断身份而被跳过");
        }
        int riskScore = score(violations, rules);
        return new RuleModels.AnalysisResult(List.copyOf(violations), riskScore, 100 - riskScore,
            List.copyOf(skipped), List.copyOf(notes));
    }

    /** 风险判定：达到或超过阈值即标风险（阈值边界 59/60/61 由用例覆盖） */
    public static boolean isRisky(int riskScore, int threshold) {
        return riskScore >= threshold;
    }

    private static int score(List<RuleModels.ViolationItem> violations,
                             List<RuleModels.RuleDefinition> rules) {
        Map<Long, Integer> deductByRule = new LinkedHashMap<>();
        for (RuleModels.RuleDefinition rule : rules == null ? List.<RuleModels.RuleDefinition>of() : rules) {
            deductByRule.put(rule.ruleId(), rule.deduct());
        }
        Map<Long, Integer> counted = new LinkedHashMap<>();
        for (RuleModels.ViolationItem item : violations) {
            counted.putIfAbsent(item.ruleId(), deductByRule.getOrDefault(item.ruleId(), item.deduct()));
        }
        int sum = counted.values().stream().mapToInt(Integer::intValue).sum();
        return Math.min(100, Math.max(0, sum));
    }

    private void collectHits(RuleModels.RuleDefinition rule, List<RuleModels.SentenceView> sentences,
                             Map<Integer, String> speakerRoles, List<RuleModels.ViolationItem> out,
                             boolean ignoreRoles) {
        for (RuleModels.SentenceView sentence : sentences) {
            if (!roleMatches(rule.roleScope(), roleOf(sentence, speakerRoles), ignoreRoles)) {
                continue;
            }
            List<RuleModels.HighlightRange> ranges = findRanges(sentence, rule.keywords());
            if (ranges.isEmpty()) {
                continue;
            }
            out.add(new RuleModels.ViolationItem(rule.ruleId(), rule.ruleVersionId(), rule.code(),
                rule.name(), MatchType.HIT, rule.roleScope(), sentence.id(), null,
                List.of(sentence.id()), ranges, rule.deduct(),
                "命中规则《" + rule.name() + "》：" + describeKeywords(rule.keywords())));
        }
    }

    private void collectRequire(RuleModels.RuleDefinition rule, List<RuleModels.SentenceView> sentences,
                                Map<Integer, String> speakerRoles, List<RuleModels.ViolationItem> out,
                                List<String> notes, boolean ignoreRoles) {
        int window = rule.windowSize() == null ? 0 : rule.windowSize();
        if (window <= 0 || rule.triggerRole() == null || rule.expectedKeywords().isEmpty()) {
            notes.add("规则《" + rule.name() + "》缺少窗口或应答词配置，已跳过");
            return;
        }
        for (int i = 0; i < sentences.size(); i++) {
            RuleModels.SentenceView trigger = sentences.get(i);
            if (!roleMatches(rule.triggerRole(), roleOf(trigger, speakerRoles), ignoreRoles)) {
                continue;
            }
            List<RuleModels.HighlightRange> triggerRanges = findRanges(trigger, rule.keywords());
            if (triggerRanges.isEmpty()) {
                continue;
            }
            // 窗口按**目标角色发言条数**计，其它角色的发言不占额度；隐藏态下不区分角色，逐条计
            int counted = 0;
            boolean answered = false;
            for (int j = i + 1; j < sentences.size() && counted < window; j++) {
                RuleModels.SentenceView next = sentences.get(j);
                if (!roleMatches(rule.roleScope(), roleOf(next, speakerRoles), ignoreRoles)) {
                    continue;
                }
                counted++;
                if (!findRanges(next, rule.expectedKeywords()).isEmpty()) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                out.add(new RuleModels.ViolationItem(rule.ruleId(), rule.ruleVersionId(), rule.code(),
                    rule.name(), MatchType.REQUIRE, rule.roleScope(), null, trigger.id(),
                    List.of(trigger.id()), triggerRanges, rule.deduct(),
                    "触发后 " + counted + " 条目标角色发言内未出现应答表述（规则《" + rule.name() + "》）"));
            }
        }
    }

    private void collectMustHave(RuleModels.RuleDefinition rule, List<RuleModels.SentenceView> sentences,
                                 Map<Integer, String> speakerRoles, List<RuleModels.ViolationItem> out,
                                 List<String> notes, boolean ignoreRoles) {
        List<RuleModels.SentenceView> scoped = sentences.stream()
            .filter(s -> roleMatches(rule.roleScope(), roleOf(s, speakerRoles), ignoreRoles))
            .toList();
        if (scoped.isEmpty()) {
            notes.add("规则《" + rule.name() + "》的角色在本通话中未出现，未产出缺失结论");
            return;
        }
        boolean present = scoped.stream().anyMatch(s -> !findRanges(s, rule.keywords()).isEmpty());
        if (present) {
            return;
        }
        out.add(new RuleModels.ViolationItem(rule.ruleId(), rule.ruleVersionId(), rule.code(),
            rule.name(), MatchType.MUST_HAVE, rule.roleScope(), null, null, List.of(), List.of(),
            rule.deduct(), "本通话中未出现必需表述：" + describeKeywords(rule.keywords())));
    }

    /** 在规范化文本中查找关键词，返回**原文** UTF-16 区间；位置映射由 TextNormalizer 保证 */
    private static List<RuleModels.HighlightRange> findRanges(RuleModels.SentenceView sentence,
                                                              List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || sentence.text() == null) {
            return List.of();
        }
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(sentence.text());
        if (normalized.text().isEmpty()) {
            return List.of();
        }
        List<RuleModels.HighlightRange> ranges = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String needle = TextNormalizer.normalize(keyword).text();
            if (needle.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int index = normalized.text().indexOf(needle, from);
                if (index < 0) {
                    break;
                }
                int[] original = normalized.toOriginalRange(index, index + needle.length());
                if (original != null) {
                    ranges.add(new RuleModels.HighlightRange(sentence.id(), original[0], original[1]));
                }
                from = index + needle.length();
            }
        }
        return ranges;
    }

    private static String roleOf(RuleModels.SentenceView sentence, Map<Integer, String> speakerRoles) {
        if (sentence.speakerId() == null || speakerRoles == null) {
            return null;
        }
        return speakerRoles.get(sentence.speakerId());
    }

    private static boolean roleMatches(String scope, String role, boolean ignoreRoles) {
        if (ignoreRoles) {
            return true;
        }
        if (scope == null || scope.isBlank() || ANY.equalsIgnoreCase(scope.trim())) {
            return true;
        }
        return role != null && scope.trim().equalsIgnoreCase(role.trim());
    }

    private static String describeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "（未配置关键词）";
        }
        return String.join("、", keywords.stream()
            .map(k -> k == null ? "" : k)
            .filter(k -> !k.isBlank())
            .map(k -> "“" + k + "”")
            .toList());
    }

    /** 供上层展示：把角色标签规范化（大小写不敏感） */
    public static String normalizeRole(String role) {
        return role == null ? null : role.trim().toUpperCase(Locale.ROOT);
    }
}
