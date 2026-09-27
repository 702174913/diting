package com.diting.service;

import com.diting.common.BusinessTime;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import com.diting.dto.AssetItemResponse;
import com.diting.entity.RecognitionTask;
import com.diting.entity.TaskAsset;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.admin.AuditLogService;
import com.diting.service.storage.ObjectStorage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务资产回显（S16）：列出一条记录下的音频与图片，并按需签发**短期**可访问地址。
 *
 * 纪律：
 * - 归属先在调用方校验（C 端按 userId、后台按可见性 + 角色），本服务只负责取数据与签发；
 * - 已过保留期或已清理的资产不签发地址（返回 purged=true），绝不给出「点开是 404」的假链接；
 * - 每次签发都留痕（只记事实与期限，不记链接本身）。
 */
@Service
public class TaskAssetService {

    private final TaskAssetMapper taskAssetMapper;
    private final RecognitionTaskMapper taskMapper;
    private final ObjectStorage objectStorage;
    private final AuditLogService auditLogService;
    private final DitingProperties props;

    public TaskAssetService(TaskAssetMapper taskAssetMapper, RecognitionTaskMapper taskMapper,
                            ObjectStorage objectStorage, AuditLogService auditLogService,
                            DitingProperties props) {
        this.taskAssetMapper = taskAssetMapper;
        this.taskMapper = taskMapper;
        this.objectStorage = objectStorage;
        this.auditLogService = auditLogService;
        this.props = props;
    }

    /** 记录下的资产清单；未过期且未清理的才签发地址 */
    public List<AssetItemResponse> list(Long taskId, String actorDomain, Long actorId,
                                        com.diting.common.AdminRole actorRole) {
        RecognitionTask task = taskMapper.selectById(taskId);
        if (task == null || task.getDeletedAt() != null
            || ("USER".equals(actorDomain) && !java.util.Objects.equals(task.getUserId(), actorId))) {
            throw new BizException(ResultCode.NOT_FOUND, "记录不存在");
        }
        if (!"USER".equals(actorDomain) && !("ADMIN".equals(actorDomain)
            && (actorRole == com.diting.common.AdminRole.ADMIN || actorRole == com.diting.common.AdminRole.OPERATOR))) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        boolean expired = task.getExpiresAt() != null && !task.getExpiresAt().isAfter(BusinessTime.now());
        List<AssetItemResponse> items = new ArrayList<>();
        for (TaskAsset asset : taskAssetMapper.selectByTask(taskId)) {
            boolean purged = asset.getObjectKey() == null || asset.getObjectKey().isBlank();
            boolean audioDenied = "AUDIO".equals(asset.getKind()) && !"USER".equals(actorDomain)
                && actorRole != com.diting.common.AdminRole.ADMIN;
            if (expired || purged || audioDenied) {
                // 到期或已清理：不签发地址，界面显示「原件已清理」
                items.add(AssetItemResponse.withoutUrl(asset));
                continue;
            }
            String url = objectStorage.presignGet(asset.getObjectKey(),
                Duration.ofMinutes(props.getMedia().getAssetUrlTtlMinutes())).url();
            LocalDateTime expiresAt = BusinessTime.now().plusMinutes(props.getMedia().getAssetUrlTtlMinutes());
            auditLogService.success(actorDomain, actorId, AuditLogService.ACTION_ASSET_PRESIGN,
                "TASK", String.valueOf(taskId), AuditLogService.detail(
                    "assetId", asset.getId(), "kind", asset.getKind(), "ttlMinutes", props.getMedia().getAssetUrlTtlMinutes()));
            items.add(AssetItemResponse.of(asset, url, expiresAt.toString()));
        }
        return items;
    }
}
