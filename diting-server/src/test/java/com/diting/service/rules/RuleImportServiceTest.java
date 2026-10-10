package com.diting.service.rules;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.dto.RuleImportApply;
import com.diting.dto.RuleImportPrecheck;
import com.diting.dto.RuleSaveRequest;
import com.diting.entity.AuditRule;
import com.diting.entity.AuditRuleVersion;
import com.diting.mapper.AuditRuleMapper;
import com.diting.mapper.AuditRuleVersionMapper;
import com.diting.mapper.RuleSetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 规则批量导入：解析、预检报告、落库隔离（RULE-11）。
 *
 * 用 spy 包住真实的 RuleService，让「预检与落库共用同一份业务校验」这件事真的被跑到；
 * 只把 findByCode 打成桩，用来构造「这一行是新增还是改版」。
 */
class RuleImportServiceTest {

    private static final String HEADER = "code,name,groupName,matchType,roleScope,triggerRole,windowSize,keywords,expectedKeywords,deduct,description";

    private final Map<Long, AuditRule> rules = new HashMap<>();
    private final Map<Long, AuditRuleVersion> versions = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(200);
    private RuleImportService service;

    @BeforeEach
    void setUp() {
        rules.clear();
        versions.clear();
        idSeq.set(200);

        AuditRuleMapper ruleMapper = mock(AuditRuleMapper.class);
        AuditRuleVersionMapper versionMapper = mock(AuditRuleVersionMapper.class);

        when(ruleMapper.insert(any(AuditRule.class))).thenAnswer(inv -> {
            AuditRule row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            rules.put(row.getId(), row);
            return 1;
        });
        when(ruleMapper.selectById(any())).thenAnswer(inv -> rules.get(inv.getArgument(0)));
        when(ruleMapper.update(any(AuditRule.class), any())).thenAnswer(inv -> {
            AuditRule patch = inv.getArgument(0);
            AuditRule stored = rules.get(patch.getId());
            stored.setCurrentVersion(patch.getCurrentVersion());
            return 1;
        });
        when(versionMapper.insert(any(AuditRuleVersion.class))).thenAnswer(inv -> {
            AuditRuleVersion row = inv.getArgument(0);
            row.setId(idSeq.incrementAndGet());
            versions.put(row.getId(), row);
            return 1;
        });
        when(versionMapper.selectOne(any())).thenAnswer(inv -> versions.values().stream()
            .max(Comparator.comparingInt(AuditRuleVersion::getVersion))
            .orElse(null));

        RuleService real = new RuleService(ruleMapper, versionMapper, mock(RuleSetMapper.class),
            new com.fasterxml.jackson.databind.ObjectMapper());
        RuleService spy = spy(real);
        // 库里已有一条 EXIST：导入它应当是「改版」而不是「新增」
        seedExisting(spy);
        service = new RuleImportService(spy);
    }

    private void seedExisting(RuleService spy) {
        AuditRule existing = new AuditRule();
        existing.setId(1L);
        existing.setCode("EXIST");
        existing.setCurrentVersion(3);
        existing.setEnabled(true);
        rules.put(1L, existing);

        AuditRuleVersion version = new AuditRuleVersion();
        version.setId(1L);
        version.setRuleId(1L);
        version.setVersion(3);
        version.setGroupName("默认分组");
        version.setName("已存在的规则");
        version.setDescription("平台依据");
        version.setMatchType("hit");
        version.setRoleScope("AGENT");
        version.setKeywords("[\"旧词\"]");
        version.setExpectedKeywords("[]");
        version.setDeduct(10);
        versions.put(1L, version);

        // 用 doReturn/doThrow 打桩 spy：直接 when(spy.method(...)) 会先执行真实方法
        doReturn(new com.diting.dto.RuleResponse(
            1L, "EXIST", 3, "默认分组", "已存在的规则", "平台依据", "hit", "AGENT",
            null, List.of("旧词"), List.of(), null, 10, true, null, null)).when(spy).findByCode("EXIST");
    }

    private static String hit(String code, String name, String keywords, String deduct) {
        return String.join(",", code, name, "默认分组", "hit", "AGENT", "", "", keywords, "", deduct, "平台依据");
    }

    // ---------------- 解析 ----------------

    @Test
    @DisplayName("CSV 解析：双引号包裹、\"\" 转义、CRLF、UTF-8 BOM 与末尾空行")
    void parsesCsvQuirks() {
        String bom = "\uFEFFcode,name\r\n\"A,B\",\"他说\"\"你好\"\"\"\r\nC,D\r\n\r\n";
        List<List<String>> rows = RuleImportService.parseCsv(bom);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("code", "name");
        assertThat(rows.get(1)).containsExactly("A,B", "他说\"你好\"");
        assertThat(rows.get(2)).containsExactly("C", "D");
    }

    @Test
    @DisplayName("关键词分隔：顿号/中英文逗号/分号/竖线/空白，去重且保序")
    void splitsKeywords() {
        assertThat(RuleImportService.splitKeywords("催收、逾期,威胁；恐吓|骚扰  催收"))
            .containsExactly("催收", "逾期", "威胁", "恐吓", "骚扰");
        assertThat(RuleImportService.splitKeywords("  ")).isEmpty();
    }

    // ---------------- 预检 ----------------

    @Test
    @DisplayName("预检：缺必需列直接 422，不逐行猜")
    void rejectsMissingHeader() {
        String content = "code,name\nA,甲";

        assertThatThrownBy(() -> service.precheck(content))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("表头缺少必需列")
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
    }

    @Test
    @DisplayName("预检：新增 / 改版 / 报错三类分开计数，且不写库")
    void precheckClassifiesRows() {
        String content = String.join("\n",
            HEADER,
            hit("NEW-1", "新规则一", "催收、逾期", "20"),
            hit("EXIST", "改过的规则", "催收", "30"),
            hit("BAD-1", "缺关键词的规则", "", "20"),
            hit("BAD-2", "扣分非法的规则", "催收", "abc"));

        RuleImportPrecheck report = service.precheck(content);

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.createCount()).isEqualTo(1);
        assertThat(report.updateCount()).isEqualTo(1);
        assertThat(report.errorCount()).isEqualTo(2);
        // 预检只看不写：库里还是只有种子那一条
        assertThat(rules).hasSize(1);

        assertThat(report.rows()).extracting("action").containsExactly("CREATE", "UPDATE", "ERROR", "ERROR");
        // 行号对应用户表格（表头是第 1 行）
        assertThat(report.rows().get(0).line()).isEqualTo(2);
        assertThat(report.rows().get(2).messages()).contains("至少需要一个关键词");
        assertThat(report.rows().get(3).messages()).anySatisfy(message ->
            assertThat(message).contains("deduct 需为整数"));
        // 改版行要带出库里当前版本，供落库时的乐观锁基准
        assertThat(report.rows().get(1).currentVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("预检：同一文件里编码重复，第二行报错并指出首次出现的位置")
    void flagsDuplicateCodeInFile() {
        String content = String.join("\n",
            HEADER,
            hit("DUP", "第一条", "催收", "20"),
            hit("DUP", "第二条", "催收", "20"));

        RuleImportPrecheck report = service.precheck(content);

        assertThat(report.createCount()).isEqualTo(1);
        assertThat(report.errorCount()).isEqualTo(1);
        assertThat(report.rows().get(1).messages()).anySatisfy(message ->
            assertThat(message).contains("编码重复").contains("第 2 行"));
    }

    @Test
    @DisplayName("预检：空白行跳过，不计入总数")
    void skipsBlankLines() {
        String content = String.join("\n", HEADER, hit("NEW-1", "规则", "催收", "20"), ",,,,,,,,,,");

        assertThat(service.precheck(content).total()).isEqualTo(1);
    }

    // ---------------- 落库 ----------------

    @Test
    @DisplayName("落库：新增走 create、改版走 update，版本号推进")
    void appliesCreatesAndUpdates() {
        String content = String.join("\n",
            HEADER,
            hit("NEW-1", "新规则", "催收", "20"),
            hit("EXIST", "改过的规则", "催收", "30"));

        RuleImportApply result = service.apply(content);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(rules).hasSize(2);
        // 同 code 追加新版本，而不是覆盖旧行
        assertThat(rules.get(1L).getCurrentVersion()).isEqualTo(4);
        assertThat(versions.values())
            .filteredOn(v -> v.getRuleId().equals(1L))
            .extracting(AuditRuleVersion::getVersion)
            .containsExactlyInAnyOrder(3, 4);
    }

    @Test
    @DisplayName("落库：单行校验失败不影响其他行，失败行带原因返回")
    void isolatesRowFailures() {
        String content = String.join("\n",
            HEADER,
            hit("NEW-1", "好的一行", "催收", "20"),
            hit("BAD-1", "坏的一行", "", "20"),
            hit("NEW-2", "另一行好的", "逾期", "10"));

        RuleImportApply result = service.apply(content);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo("BAD-1");
            assertThat(failure.line()).isEqualTo(3);
            assertThat(failure.messages()).contains("至少需要一个关键词");
        });
        // 好的两行确实落库了
        assertThat(rules.values()).extracting(AuditRule::getCode).contains("NEW-1", "NEW-2");
    }

    @Test
    @DisplayName("落库：require 规则缺触发角色/应答词被拦下（用的是 RuleService 那份校验）")
    void enforcesSemanticRulesOnImport() {
        String requireRow = String.join(",", "REQ-1", "需应答类规则", "默认分组", "require", "AGENT", "AGENT", "3",
            "还款安排", "", "20", "平台依据");
        String content = String.join("\n", HEADER, requireRow);

        RuleImportPrecheck report = service.precheck(content);

        assertThat(report.errorCount()).isEqualTo(1);
        assertThat(report.rows().get(0).messages()).contains("require 规则必须配置应答词");
    }

    @Test
    @DisplayName("落库：改版遇到版本冲突（并发改过）被记为该行失败，不抛给调用方")
    void reportsOptimisticLockConflictAsRowFailure() {
        RuleService failing = spy(new RuleService(mock(AuditRuleMapper.class),
            mock(AuditRuleVersionMapper.class), mock(RuleSetMapper.class),
            new com.fasterxml.jackson.databind.ObjectMapper()));
        doReturn(new com.diting.dto.RuleResponse(
            1L, "EXIST", 3, "默认分组", "已存在的规则", "平台依据", "hit", "AGENT",
            null, List.of("旧词"), List.of(), null, 10, true, null, null)).when(failing).findByCode("EXIST");
        doThrow(new BizException(ResultCode.CONFLICT, "规则已被修改（当前版本 4），请刷新后重试"))
            .when(failing).update(any(), anyLong(), any(RuleSaveRequest.class));
        RuleImportService importing = new RuleImportService(failing);

        RuleImportApply result = importing.apply(String.join("\n", HEADER, hit("EXIST", "改过的", "催收", "20")));

        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures().get(0).messages().get(0)).contains("请刷新后重试");
    }

    @Test
    @DisplayName("空内容直接 422")
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> service.precheck(""))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("文件是空的");
    }

    @Test
    @DisplayName("RuleService.validate 会收集全部问题（导入报告要一次说清）")
    void validateCollectsAllProblems() {
        RuleService real = new RuleService(mock(AuditRuleMapper.class),
            mock(AuditRuleVersionMapper.class), mock(RuleSetMapper.class),
            new com.fasterxml.jackson.databind.ObjectMapper());
        RuleSaveRequest broken = new RuleSaveRequest(
            "", null, "", "", "不存在的类型", "WRONG", null, List.of(), List.of(), null, 999);

        List<String> problems = real.validate(broken);

        assertThat(problems).hasSizeGreaterThanOrEqualTo(4);
        assertThat(problems).contains("规则编码需为 1-64 位", "扣分需为 0-100 的整数", "至少需要一个关键词");
    }

    @Test
    @DisplayName("列表行号与文件一致：第一行数据是第 2 行")
    void lineNumbersMatchFile() {
        String content = String.join("\n", HEADER, hit("NEW-1", "规则", "催收", "20"));
        List<Integer> lines = new ArrayList<>();
        service.precheck(content).rows().forEach(row -> lines.add(row.line()));

        assertThat(lines).containsExactly(2);
    }
}
