package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.LoginUserHolder;
import com.diting.config.RequireRole;
import com.diting.dto.AssetItemResponse;
import com.diting.entity.RecognitionTask;
import com.diting.service.RecognitionTaskService;
import com.diting.service.TaskAssetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务资产回显（S16）：一条记录下的音频与图片。
 *
 * C 端与后台**独立路由、独立身份域**：
 * - `/api/task/{id}/assets`：仅本人记录，未登录拿不到（拦截器强制）；
 * - `/api/admin/recordings/{id}/assets`：ADMIN 与 OPERATOR 可看（用户需求：后台也能看图片），
 *   INVESTOR 不含个体数据，一律 403。
 * 地址是短期预签名，每次签发都留痕；已过保留期或已清理的资产不给地址。
 */
@RestController
public class AssetController {

    private final TaskAssetService taskAssetService;
    private final RecognitionTaskService taskService;

    public AssetController(TaskAssetService taskAssetService, RecognitionTaskService taskService) {
        this.taskAssetService = taskAssetService;
        this.taskService = taskService;
    }

    @GetMapping("/api/task/{id}/assets")
    public R<List<AssetItemResponse>> listForUser(@PathVariable Long id) {
        Long userId = LoginUserHolder.getUserId();
        RecognitionTask task = taskService.requireOwned(id, userId);
        return R.ok(taskAssetService.list(task.getId(), "USER", userId, null));
    }

    @GetMapping("/api/admin/recordings/{id}/assets")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR})
    public R<List<AssetItemResponse>> listForAdmin(@PathVariable Long id) {
        var actor = LoginUserHolder.get();
        RecognitionTask task = taskService.requireVisibleForAdmin(id);
        return R.ok(taskAssetService.list(task.getId(), actor.domain().name(), actor.id(), actor.role()));
    }
}
