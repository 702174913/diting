package com.diting.dto;

import java.util.List;

/**
 * 批量导入的单行结果（预检与落库共用）。
 *
 * @param line           表格行号（含表头，从 1 起），方便用户对着自己的文件定位
 * @param action         CREATE / UPDATE / ERROR
 * @param currentVersion 库里当前版本（UPDATE 时有值，也是落库时的乐观锁基准）
 * @param messages       报错信息（ERROR 时至少一条）
 */
public record RuleImportRow(
    int line,
    String code,
    String name,
    String action,
    Integer currentVersion,
    List<String> messages
) {
    public static RuleImportRow created(int line, String code, String name) {
        return new RuleImportRow(line, code, name, "CREATE", null, List.of());
    }

    public static RuleImportRow updated(int line, String code, String name, Integer currentVersion) {
        return new RuleImportRow(line, code, name, "UPDATE", currentVersion, List.of());
    }

    public static RuleImportRow error(int line, String code, String name, List<String> messages) {
        return new RuleImportRow(line, code, name, "ERROR", null, List.copyOf(messages));
    }
}
