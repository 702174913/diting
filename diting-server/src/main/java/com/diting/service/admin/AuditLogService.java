package com.diting.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.diting.common.BusinessTime;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.entity.AuditLog;
import com.diting.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志（开发文档 §6.4、§9）。
 *
 * 只记元数据：谁、在哪个身份域、做了什么、对哪个目标、结果如何、来源 IP。
 * **禁止写入**：对话原文、签名 URL、密钥、密码与任何个人敏感信息——
 * detail 只允许放状态、计数、版本号这类可解释字段。
 */
@Service
public class AuditLogService {

    public static final String ACTION_CONFIG_SAVE = "CONFIG_SAVE";
    public static final String ACTION_RULE_CREATE = "RULE_CREATE";
    public static final String ACTION_RULE_UPDATE = "RULE_UPDATE";
    public static final String ACTION_RULE_DISABLE = "RULE_DISABLE";
    public static final String ACTION_ACCOUNT_CREATE = "ACCOUNT_CREATE";
    public static final String ACTION_ACCOUNT_UPDATE = "ACCOUNT_UPDATE";
    public static final String ACTION_SPEAKER_MAP = "SPEAKER_MAP_CONFIRM";
    public static final String ACTION_AUDIO_PRESIGN = "AUDIO_PRESIGN";
    /** 图片等资产地址签发（S16）：只记签发事实与期限，不记链接 */
    public static final String ACTION_ASSET_PRESIGN = "ASSET_PRESIGN";
    public static final String ACTION_TASK_DELETE = "TASK_DELETE";
    public static final String ACTION_REPORT_REANALYZE = "REPORT_REANALYZE";
    public static final String ACTION_ERASE_ALL = "ERASE_ALL";
    /** 法律条款库（V13）：新增/编辑/停用，以及规则绑定条款（不生成规则新版本） */
    public static final String ACTION_LEGAL_CLAUSE_CREATE = "LEGAL_CLAUSE_CREATE";
    public static final String ACTION_LEGAL_CLAUSE_UPDATE = "LEGAL_CLAUSE_UPDATE";
    public static final String ACTION_LEGAL_CLAUSE_DISABLE = "LEGAL_CLAUSE_DISABLE";
    public static final String ACTION_RULE_LEGAL_CLAUSE_BIND = "RULE_LEGAL_CLAUSE_BIND";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_DENIED = "DENIED";
    public static final String RESULT_FAILED = "FAILED";

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper json;

    public AuditLogService(AuditLogMapper auditLogMapper, ObjectMapper json) {
        this.auditLogMapper = auditLogMapper;
        this.json = json;
    }

    /** 记录一条审计；detail 只允许放非敏感元数据 */
    public void record(String actorDomain, Long actorId, String action, String targetType, String targetId,
                       String result, Map<String, Object> detail) {
        AuditLog row = new AuditLog();
        row.setActorDomain(actorDomain);
        row.setActorId(actorId);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId == null ? null : truncate(String.valueOf(targetId), 64));
        row.setResult(result);
        row.setDetail(toJson(detail));
        row.setCreatedAt(BusinessTime.now());
        auditLogMapper.insert(row);
    }

    public void success(String actorDomain, Long actorId, String action, String targetType, String targetId,
                        Map<String, Object> detail) {
        record(actorDomain, actorId, action, targetType, targetId, RESULT_SUCCESS, detail);
    }

    public PageResult<AuditLog> list(PageQuery query, String action, String actorDomain) {
        Page<AuditLog> page = auditLogMapper.selectPage(Page.of(query.page(), query.pageSize()),
            new LambdaQueryWrapper<AuditLog>()
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(actorDomain != null && !actorDomain.isBlank(), AuditLog::getActorDomain, actorDomain)
                .orderByDesc(AuditLog::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), query);
    }

    /** 便于测试与排查：把 detail 读回成 Map */
    public Map<String, Object> detailOf(AuditLog row) {
        if (row.getDetail() == null || row.getDetail().isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(row.getDetail(), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        }
        catch (Exception e) {
            return Map.of();
        }
    }

    /** 计数类 detail 的构造入口，避免各调用点各写一套 */
    public static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        }
        catch (Exception e) {
            return "{}";
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
