package com.diting.service.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE-01..RULE-10：纯函数判读。
 * 引擎不碰数据库也不调用模型，因此同一输入必须得到同一输出（RULE-10）。
 */
class RuleMatchServiceTest {

    private static final Map<Integer, String> ROLES = Map.of(0, "AGENT", 1, "CUSTOMER");

    private final RuleMatchService engine = new RuleMatchService();

    private static RuleModels.RuleDefinition hit(String name, String scope, int deduct, String... keywords) {
        return new RuleModels.RuleDefinition(1L, 11L, "R-HIT", "话术", name, "平台规则依据", MatchType.HIT,
            scope, null, List.of(keywords), List.of(), null, deduct);
    }

    private static RuleModels.RuleDefinition require(int deduct, String triggerRole, String expectRole,
                                                     int window, List<String> triggers, List<String> expects) {
        return new RuleModels.RuleDefinition(2L, 22L, "R-REQ", "话术", "需回应身份", "平台规则依据",
            MatchType.REQUIRE, expectRole, triggerRole, triggers, expects, window, deduct);
    }

    private static RuleModels.RuleDefinition mustHave(int deduct, String scope, String... keywords) {
        return new RuleModels.RuleDefinition(3L, 33L, "R-MUST", "话术", "必须自报家门", "平台规则依据",
            MatchType.MUST_HAVE, scope, null, List.of(keywords), List.of(), null, deduct);
    }

    private static RuleModels.SentenceView sentence(long id, int seq, Integer speaker, String text) {
        return new RuleModels.SentenceView(id, seq, speaker, text);
    }

    @Test
    @DisplayName("RULE-01 hit 正反例：命中给区间，不命中不给证据")
    void hitRule() {
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 0, "你好，我是某某公司的客服。"),
            sentence(2L, 2, 1, "我知道了。"));

        RuleModels.AnalysisResult positive = engine.analyze(sentences, ROLES, true,
            List.of(hit("禁用承诺", "AGENT", 20, "客服")));
        assertThat(positive.violations()).hasSize(1);
        RuleModels.ViolationItem item = positive.violations().get(0);
        assertThat(item.sentenceId()).isEqualTo(1L);
        assertThat(item.ranges()).hasSize(1);
        assertThat(item.ranges().get(0).start()).isEqualTo(10);
        assertThat(item.ranges().get(0).end()).isEqualTo(12);
        assertThat(positive.riskScore()).isEqualTo(20);

        RuleModels.AnalysisResult negative = engine.analyze(sentences, ROLES, true,
            List.of(hit("禁用承诺", "AGENT", 20, "保证免息")));
        assertThat(negative.violations()).isEmpty();
        assertThat(negative.riskScore()).isZero();
        assertThat(negative.complianceScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("RULE-02 require 窗口按目标角色条数计：第 N 条内回应算通过，第 N+1 条才算缺失")
    void requireWindow() {
        // 触发句后：客户第 1 句（无关）、催收插话（不计入窗口）、客户第 2 句（回应）
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 0, "请提供你的身份证号。"),
            sentence(2L, 2, 1, "好的，我先记一下。"),
            sentence(3L, 3, 0, "请尽快提供。"),
            sentence(4L, 4, 1, "我确认这是本人号码。"));

        // 窗口 2：客户第 2 句出现回应词 → 通过
        RuleModels.AnalysisResult ok = engine.analyze(sentences, ROLES, true,
            List.of(require(30, "AGENT", "CUSTOMER", 2, List.of("身份证号"), List.of("本人号码", "本人"))));
        assertThat(ok.violations()).isEmpty();

        // 窗口 1：只看到客户第 1 句 → 缺失，且不给具体句（不假造应答原句）
        RuleModels.AnalysisResult missing = engine.analyze(sentences, ROLES, true,
            List.of(require(30, "AGENT", "CUSTOMER", 1, List.of("身份证号"), List.of("本人号码"))));
        // 窗口 1：只有第一个触发句构成缺失；第二个触发句后客户第 1 句就回应了
        assertThat(missing.violations()).hasSize(1);
        assertThat(missing.violations()).allSatisfy(item -> {
            assertThat(item.sentenceId()).isNull();
            assertThat(item.triggerSentenceId()).isNotNull();
            assertThat(item.ranges()).isNotEmpty();
        });
        assertThat(missing.riskScore()).isEqualTo(30);
    }

    @Test
    @DisplayName("RULE-02 错误角色不触发：触发词出现在客户方时不产生缺失")
    void requireWrongRoleDoesNotTrigger() {
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 1, "身份证号我记下了。"),
            sentence(2L, 2, 0, "好的。"));

        RuleModels.AnalysisResult result = engine.analyze(sentences, ROLES, true,
            List.of(require(30, "AGENT", "CUSTOMER", 2, List.of("身份证号"), List.of("本人"))));

        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("RULE-03 must-have：出现即通过，缺失给通话级缺失且没有具体句")
    void mustHaveRule() {
        List<RuleModels.SentenceView> withSelfIntro = List.of(
            sentence(1L, 1, 0, "您好，我是某某公司的。"),
            sentence(2L, 2, 1, "嗯。"));
        assertThat(engine.analyze(withSelfIntro, ROLES, true, List.of(mustHave(10, "AGENT", "我是某某公司")))
            .violations()).isEmpty();

        List<RuleModels.SentenceView> withoutSelfIntro = List.of(
            sentence(1L, 1, 0, "你什么时候还钱？"),
            sentence(2L, 2, 1, "再等等。"));
        RuleModels.AnalysisResult missing = engine.analyze(withoutSelfIntro, ROLES, true,
            List.of(mustHave(10, "AGENT", "我是某某公司")));
        assertThat(missing.violations()).hasSize(1);
        assertThat(missing.violations().get(0).sentenceId()).isNull();
        assertThat(missing.violations().get(0).evidenceSentenceIds()).isEmpty();
        assertThat(missing.violations().get(0).note()).contains("未出现必需表述");
    }

    @Test
    @DisplayName("RULE-03 该角色在通话中未出现：不产出缺失结论，只记录说明")
    void mustHaveWithoutRole() {
        List<RuleModels.SentenceView> onlyCustomer = List.of(sentence(1L, 1, 1, "喂？"));

        RuleModels.AnalysisResult result = engine.analyze(onlyCustomer, ROLES, true,
            List.of(mustHave(10, "AGENT", "我是某某公司")));

        assertThat(result.violations()).isEmpty();
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("角色在本通话中未出现"));
    }

    @Test
    @DisplayName("RULE-04 重复命中不重复扣分，但保留全部证据")
    void deductOncePerRule() {
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 0, "保证免息，保证免息。"),
            sentence(2L, 2, 0, "我再保证免息一次。"));

        RuleModels.AnalysisResult result = engine.analyze(sentences, ROLES, true,
            List.of(hit("禁用承诺", "AGENT", 30, "保证免息")));

        assertThat(result.violations()).hasSize(2);
        assertThat(result.riskScore()).isEqualTo(30);
        assertThat(result.violations().get(0).ranges()).hasSize(2);
    }

    @Test
    @DisplayName("RULE-05 多规则同句：各自独立计分，证据不混")
    void multipleRulesSameSentence() {
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 0, "你必须今天还钱，否则上门。"));
        List<RuleModels.RuleDefinition> rules = List.of(
            new RuleModels.RuleDefinition(1L, 11L, "R1", "g", "威胁上门", "d", MatchType.HIT, "AGENT",
                null, List.of("上门"), List.of(), null, 40),
            new RuleModels.RuleDefinition(2L, 22L, "R2", "g", "强制用语", "d", MatchType.HIT, "AGENT",
                null, List.of("必须"), List.of(), null, 25));

        RuleModels.AnalysisResult result = engine.analyze(sentences, ROLES, true, rules);

        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations()).extracting(RuleModels.ViolationItem::ruleId)
            .containsExactlyInAnyOrder(1L, 2L);
        assertThat(result.riskScore()).isEqualTo(65);
        assertThat(result.complianceScore()).isEqualTo(35);
    }

    @Test
    @DisplayName("RULE-08 阈值边界：59 不标风险、60 与 61 标风险")
    void thresholdBoundary() {
        assertThat(RuleMatchService.isRisky(59, 60)).isFalse();
        assertThat(RuleMatchService.isRisky(60, 60)).isTrue();
        assertThat(RuleMatchService.isRisky(61, 60)).isTrue();
    }

    @Test
    @DisplayName("RULE-09 目标角色功能隐藏：未确认角色也按「任意角色」判读（不区分说话人）")
    void roleTargetingHidden() {
        List<RuleModels.SentenceView> sentences = List.of(sentence(1L, 1, 0, "保证免息"));

        // 隐藏态：角色限定不生效，AGENT 限定规则在未确认角色时照样命中
        RuleModels.AnalysisResult result = engine.analyze(sentences, Map.of(), false,
            List.of(hit("禁用承诺", "AGENT", 30, "保证免息")));

        assertThat(result.violations()).hasSize(1);
        assertThat(result.riskScore()).isEqualTo(30);
        assertThat(result.skipped()).isEmpty();

        // require 类：触发角色写的是 CUSTOMER，隐藏态下任意说话人都能触发窗口判定
        RuleModels.AnalysisResult requireResult = engine.analyze(
            List.of(sentence(1L, 1, 0, "我不认识这个人"), sentence(2L, 2, 1, "好的，我知道了")),
            Map.of(), false,
            List.of(require(15, "CUSTOMER", "AGENT", 3, List.of("我不认识这个人"), List.of("核实"))));
        assertThat(requireResult.violations()).hasSize(1);
        assertThat(requireResult.violations().get(0).triggerSentenceId()).isEqualTo(1L);

        // 「任意角色」的规则同样不受影响
        RuleModels.AnalysisResult anyScope = engine.analyze(sentences, Map.of(), false,
            List.of(hit("通用禁用词", "ANY", 30, "保证免息")));
        assertThat(anyScope.violations()).hasSize(1);
    }

    @Test
    @DisplayName("RULE-10 同一输入重复判读结果完全一致")
    void deterministic() {
        List<RuleModels.SentenceView> sentences = List.of(
            sentence(1L, 1, 0, "您好，我是某某公司。"),
            sentence(2L, 2, 1, "你说。"),
            sentence(3L, 3, 0, "保证免息。"));
        List<RuleModels.RuleDefinition> rules = List.of(
            hit("禁用承诺", "AGENT", 30, "保证免息"),
            mustHave(10, "AGENT", "我是某某公司"));

        RuleModels.AnalysisResult first = engine.analyze(sentences, ROLES, true, rules);
        RuleModels.AnalysisResult second = engine.analyze(sentences, ROLES, true, rules);

        assertThat(first.violations()).isEqualTo(second.violations());
        assertThat(first.riskScore()).isEqualTo(second.riskScore());
        assertThat(first.skipped()).isEqualTo(second.skipped());
    }

    @Test
    @DisplayName("句子顺序打乱也会按 sequence 归一，区间不错位")
    void orderIndependent() {
        List<RuleModels.SentenceView> shuffled = List.of(
            sentence(2L, 2, 1, "我知道了。"),
            sentence(1L, 1, 0, "你好，我是某某公司的。"));
        List<RuleModels.SentenceView> ordered = List.of(
            sentence(1L, 1, 0, "你好，我是某某公司的。"),
            sentence(2L, 2, 1, "我知道了。"));

        assertThat(engine.analyze(shuffled, ROLES, true, List.of(mustHave(10, "AGENT", "我是某某公司")))
            .violations())
            .isEqualTo(engine.analyze(ordered, ROLES, true, List.of(mustHave(10, "AGENT", "我是某某公司")))
                .violations());
    }
}
