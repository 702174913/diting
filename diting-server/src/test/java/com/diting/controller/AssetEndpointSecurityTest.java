package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.LoginDomain;
import com.diting.entity.AdminUser;
import com.diting.entity.RecognitionTask;
import com.diting.entity.TaskAsset;
import com.diting.mapper.AdminUserMapper;
import com.diting.mapper.UserMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.*;
import com.diting.service.admin.AuditLogService;
import com.diting.service.report.ReportService;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AssetController.class, AdminRecordingController.class})
@Import({AdminEndpointSecurityTest.Tokens.class, TaskAssetService.class})
class AssetEndpointSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @MockBean AdminUserMapper admins;
    @MockBean UserMapper users;
    @MockBean AdminUserService adminService;
    @MockBean RecognitionTaskMapper tasks;
    @MockBean TaskAssetMapper assets;
    @MockBean RecognitionTaskService taskService;
    @MockBean ReportService reports;
    @MockBean DeletionService deletion;
    @MockBean OssService oss;
    @MockBean ObjectStorage storage;
    @MockBean AuditLogService audit;

    private void fixtures(AdminRole role) throws Exception {
        AdminUser admin = new AdminUser(); admin.setId(2L); admin.setRole(role.name()); admin.setEnabled(true);
        when(admins.selectById(2L)).thenReturn(admin);
        RecognitionTask task = new RecognitionTask(); task.setId(1L); task.setUserId(7L);
        task.setOssObject("audio"); task.setMediaKind("AUDIO");
        when(tasks.selectById(1L)).thenReturn(task);
        when(taskService.requireVisibleForAdmin(1L)).thenReturn(task);
        TaskAsset asset = new TaskAsset(); asset.setId(3L); asset.setKind("AUDIO"); asset.setObjectKey("audio");
        when(assets.selectByTask(1L)).thenReturn(List.of(asset));
        when(storage.presignGet(anyString(), any())).thenReturn(new ObjectStorage.PresignedRequest("https://test/audio", "GET", Map.of(), Instant.now()));
        when(oss.presignDownload(anyString(), any())).thenReturn(URI.create("https://test/audio").toURL());
    }
    @Test void operatorAssetsMustNotBypassAdminOnlyAudio() throws Exception {
        fixtures(AdminRole.OPERATOR);
        String auth = "Bearer " + tokens.issue(2L, LoginDomain.ADMIN);
        mvc.perform(get("/api/admin/recordings/1/audio").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/recordings/1/assets").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].url").doesNotExist());
        verifyNoInteractions(storage, oss);
    }
    @Test void adminCanSignThroughBothRoutes() throws Exception {
        fixtures(AdminRole.ADMIN);
        String auth = "Bearer " + tokens.issue(2L, LoginDomain.ADMIN);
        mvc.perform(get("/api/admin/recordings/1/audio").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value("https://test/audio"));
        mvc.perform(get("/api/admin/recordings/1/assets").header("Authorization", auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].url").value("https://test/audio"));
    }
    @Test void investorAndUserDomainCannotReadAdminOriginals() throws Exception {
        fixtures(AdminRole.INVESTOR);
        for (LoginDomain domain : LoginDomain.values()) {
            for (String path : List.of("audio", "assets")) {
                mvc.perform(get("/api/admin/recordings/1/" + path)
                    .header("Authorization", "Bearer " + tokens.issue(2L, domain))).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(storage, oss);
    }
}
