package com.diting.service;

import com.diting.common.AdminRole;
import com.diting.common.BizException;
import com.diting.config.DitingProperties;
import com.diting.entity.RecognitionTask;
import com.diting.entity.TaskAsset;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.admin.AuditLogService;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskAssetServiceTest {
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TaskAssetService service = new TaskAssetService(assets, tasks, storage, audit, new DitingProperties());
    private RecognitionTask task;

    @BeforeEach void setup() {
        task = new RecognitionTask(); task.setId(1L); task.setUserId(7L);
        when(tasks.selectById(1L)).thenReturn(task);
        when(assets.selectByTask(1L)).thenReturn(List.of(asset(1L, "AUDIO"), asset(2L, "IMAGE")));
        when(storage.presignGet(anyString(), any())).thenAnswer(call ->
            new ObjectStorage.PresignedRequest("https://test/" + call.getArgument(0), "GET", Map.of(), Instant.now()));
    }
    private TaskAsset asset(Long id, String kind) {
        TaskAsset row = new TaskAsset(); row.setId(id); row.setKind(kind); row.setObjectKey(kind); return row;
    }
    @Test void operatorCannotGetAudioButCanSeeImages() {
        var result = service.list(1L, "ADMIN", 2L, AdminRole.OPERATOR);
        assertThat(result.get(0).url()).isNull();
        assertThat(result.get(1).url()).isNotNull();
        verify(storage, never()).presignGet(eq("AUDIO"), any());
    }
    @Test void adminAndOwnerCanGetAudio() {
        assertThat(service.list(1L, "ADMIN", 2L, AdminRole.ADMIN).get(0).url()).isNotNull();
        assertThat(service.list(1L, "USER", 7L, null).get(0).url()).isNotNull();
    }
    @Test void unauthorizedDomainsAndRolesFailClosed() {
        assertThatThrownBy(() -> service.list(1L, "ADMIN", 2L, AdminRole.INVESTOR)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.list(1L, "ADMIN", 2L, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.list(1L, "UNKNOWN", 2L, AdminRole.ADMIN)).isInstanceOf(BizException.class);
        verifyNoInteractions(storage, audit);
    }
    @Test void foreignOwnerAndDeletedTaskCannotSign() {
        assertThatThrownBy(() -> service.list(1L, "USER", 8L, null)).isInstanceOf(BizException.class);
        task.setDeletedAt(LocalDateTime.now());
        assertThatThrownBy(() -> service.list(1L, "ADMIN", 2L, AdminRole.ADMIN)).isInstanceOf(BizException.class);
        verifyNoInteractions(storage, audit);
    }
    @Test void expiredTaskCannotSign() {
        task.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertThat(service.list(1L, "ADMIN", 2L, AdminRole.ADMIN)).allSatisfy(item -> assertThat(item.url()).isNull());
        verifyNoInteractions(storage, audit);
    }
}
