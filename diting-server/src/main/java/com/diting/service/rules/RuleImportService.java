package com.diting.service.rules;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.dto.RuleImportApply;
import com.diting.dto.RuleImportPrecheck;
import com.diting.dto.RuleImportRow;
import com.diting.dto.RuleResponse;
import com.diting.dto.RuleSaveRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则批量导入（Excel / 数字表格另存为 CSV 的模板）。
 *
 * 两段式：
 * 1. {@link #precheck} 解析 + 逐行校验 + 与库里比对，给出「新增 / 改版 / 报错」的报告，**不写库**；
 * 2. {@link #apply} 重新解析（不信前端传来的预检结果）后逐行落库，单行失败不影响其他行。
 *
 * 为什么按行独立落库而不是整批一个事务：批量导入本质是一串互相独立、各自版本化的规则变更
 * （同 code 走乐观锁追加新版本）。整批回滚会让 99 行正确变更因为 1 行写错而全部作废，
 * 不如「成功的可核对、失败的明确列出」。
 *
 * 校验一律复用 {@link RuleService#validate}，这里不写第二套规则 —— 否则导入迟早与单条编辑不一致。
 */
@Service
public class RuleImportService {

    /**
     * 模板列名（与规则接口字段同构）；大小写与首尾空白、下划线都会归一化，
     * 所以这里的比对基准也必须是归一化后的形式（不带下划线、全小写）。
     */
    static final List<String> REQUIRED_COLUMNS = List.of("code", "name", "matchtype", "keywords", "deduct", "description");

    private static final String DEFAULT_GROUP = "默认分组";

    private final RuleService ruleService;

    public RuleImportService(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    // ---------------- 预检 ----------------

    public RuleImportPrecheck precheck(String content) {
        List<Map<String, String>> rows = readRows(content);
        List<RuleImportRow> results = new ArrayList<>();
        Map<String, Integer> seenCodes = new LinkedHashMap<>();
        int create = 0;
        int update = 0;
        int error = 0;

        for (int index = 0; index < rows.size(); index++) {
            int line = index + 2; // 第 1 行是表头
            Map<String, String> row = rows.get(index);
            String code = value(row, "code");
            String name = value(row, "name");
            if (isBlankRow(row)) {
                continue;
            }

            List<String> problems = new ArrayList<>();
            Parsed parsed = parse(row);
            if (parsed.problems().isEmpty()) {
                // 字段级别先立住，语义校验才有意义（否则一个 "deduct=abc" 会同时报两条）
                problems.addAll(ruleService.validate(parsed.request()));
            }
            else {
                problems.addAll(parsed.problems());
            }
            if (!code.isBlank()) {
                Integer firstLine = seenCodes.putIfAbsent(code, line);
                if (firstLine != null) {
                    problems.add("同一文件里编码重复（第 " + firstLine + " 行已出现）");
                }
            }

            RuleResponse existing = code.isBlank() ? null : ruleService.findByCode(code);
            if (!problems.isEmpty()) {
                error++;
                results.add(RuleImportRow.error(line, code, name, problems));
            }
            else if (existing == null) {
                create++;
                results.add(RuleImportRow.created(line, code, name));
            }
            else {
                update++;
                results.add(RuleImportRow.updated(line, code, name, existing.version()));
            }
        }
        return new RuleImportPrecheck(create + update + error, create, update, error, results);
    }

    // ---------------- 落库 ----------------

    public RuleImportApply apply(String content) {
        List<Map<String, String>> rows = readRows(content);
        List<RuleImportRow> failures = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (int index = 0; index < rows.size(); index++) {
            int line = index + 2;
            Map<String, String> row = rows.get(index);
            if (isBlankRow(row)) {
                continue;
            }
            String code = value(row, "code");
            String name = value(row, "name");
            Parsed parsed = parse(row);

            List<String> problems = new ArrayList<>(parsed.problems());
            if (problems.isEmpty()) {
                problems.addAll(ruleService.validate(parsed.request()));
            }
            if (!problems.isEmpty()) {
                failures.add(RuleImportRow.error(line, code, name, problems));
                continue;
            }

            try {
                RuleResponse existing = ruleService.findByCode(code);
                if (existing == null) {
                    ruleService.create(parsed.request());
                    created++;
                }
                else {
                    // 用「落库这一刻」的版本做乐观锁：预检到落库之间若有人改过，这一行会被 409 拦下
                    ruleService.update(existing.ruleId(), existing.version(), parsed.request());
                    updated++;
                }
            }
            catch (BizException e) {
                failures.add(RuleImportRow.error(line, code, name, List.of(e.getMessage())));
            }
        }
        return new RuleImportApply(created, updated, failures.size(), failures);
    }

    // ---------------- 解析 ----------------

    private List<Map<String, String>> readRows(String content) {
        List<List<String>> table = parseCsv(content);
        if (table.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE, "文件是空的");
        }
        List<String> header = table.get(0).stream()
            .map(RuleImportService::normalizeHeader)
            .toList();
        List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !header.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.UNPROCESSABLE,
                "表头缺少必需列：" + String.join(", ", missing));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < cells.size() ? cells.get(c).trim() : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 极简 CSV 解析：支持双引号包裹、"" 转义、CRLF、UTF-8 BOM。
     * 只覆盖模板能表达的东西，不追求完整 CSV 规范（例如不处理多行字段）。
     */
    static List<List<String>> parseCsv(String raw) {
        List<List<String>> rows = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return rows;
        }
        String content = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (inQuotes) {
                if (current == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    }
                    else {
                        inQuotes = false;
                    }
                }
                else {
                    field.append(current);
                }
                continue;
            }
            switch (current) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // 丢掉，统一按 \n 断行
                }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> field.append(current);
            }
        }
        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        // 去掉完全空白的尾行
        rows.removeIf(cells -> cells.stream().allMatch(cell -> cell == null || cell.isBlank()));
        return rows;
    }

    private static String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static String value(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null ? "" : value;
    }

    private static boolean isBlankRow(Map<String, String> row) {
        return row.values().stream().allMatch(value -> value == null || value.isBlank());
    }

    /** 关键词分隔：顿号 / 中英文逗号 / 分号 / 竖线 / 空白，与前端编辑器的拆分口径一致 */
    static List<String> splitKeywords(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("[、,，;；|\\s]+")).stream()
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .distinct()
            .toList();
    }

    /** 解析结果：请求体 + 字段级问题。字段级问题非空时调用方不应再做语义校验 */
    private record Parsed(RuleSaveRequest request, List<String> problems) {
    }

    private static Integer parseInteger(String value, String column, List<String> problems) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        }
        catch (NumberFormatException e) {
            problems.add(column + " 需为整数，当前值：" + value);
            return null;
        }
    }

    private Parsed parse(Map<String, String> row) {
        List<String> problems = new ArrayList<>();
        Integer windowSize = parseInteger(value(row, "windowsize"), "windowSize", problems);
        Integer deduct = parseInteger(value(row, "deduct"), "deduct", problems);
        String groupName = value(row, "groupname");

        RuleSaveRequest request = new RuleSaveRequest(
            value(row, "code"),
            groupName.isBlank() ? DEFAULT_GROUP : groupName,
            value(row, "name"),
            value(row, "description"),
            value(row, "matchtype"),
            value(row, "rolescope"),
            value(row, "triggerrole"),
            splitKeywords(value(row, "keywords")),
            splitKeywords(value(row, "expectedkeywords")),
            windowSize,
            deduct
        );
        return new Parsed(request, problems);
    }
}
