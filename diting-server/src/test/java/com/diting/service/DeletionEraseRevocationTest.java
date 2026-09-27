package com.diting.service;

import com.diting.common.BizException;
import com.diting.entity.DeletionJob;
import com.diting.entity.User;
import com.diting.entity.RecognitionTask;
import com.diting.entity.TaskAsset;
import com.diting.entity.UploadSession;
import com.diting.mapper.DeletionJobMapper;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.UserMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.mapper.UploadSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeletionEraseRevocationTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    private final DeletionJobMapper jobs = mock(DeletionJobMapper.class);
    private final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    private final UploadSessionMapper sessions = mock(UploadSessionMapper.class);
    private final DeletionService service = new DeletionService(tasks, jobs, users, assets, sessions);

    @Test void eraseRevokesBeforeRegisteringCleanupInSameServiceTransaction() {
        User user = new User(); user.setId(7L);
        when(users.lockById(7L)).thenReturn(user);
        when(users.revokeForErase(7L)).thenReturn(1);
        when(tasks.selectList(any())).thenReturn(List.of());
        service.eraseAll(7L);
        var order = inOrder(users, tasks, jobs);
        order.verify(users).lockById(7L);
        order.verify(users).revokeForErase(7L);
        order.verify(tasks).selectList(any());
        order.verify(jobs).insert(any(DeletionJob.class));
    }

    @Test void eraseManifestRetainsTaskAssetsAndSignedSessionsForPostExpirySweep() {
        User user = new User(); user.setId(7L);
        when(users.lockById(7L)).thenReturn(user);
        when(users.revokeForErase(7L)).thenReturn(1);
        RecognitionTask task = new RecognitionTask(); task.setId(42L); task.setUserId(7L);
        task.setStatus("QUEUED"); task.setOssObject("recordings/7/a.m4a");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        TaskAsset image = new TaskAsset(); image.setObjectKey("images/7/b.jpg");
        when(assets.selectByTask(42L)).thenReturn(List.of(image));
        UploadSession session = new UploadSession(); session.setObjectKey("uploads/7/loose.m4a");
        when(sessions.selectList(any())).thenReturn(List.of(session));
        service.eraseAll(7L);
        var captured = org.mockito.ArgumentCaptor.forClass(DeletionJob.class);
        verify(jobs).insert(captured.capture());
        assertThat(DeletionService.pendingObjects(captured.getValue().getRemainingObjects()))
            .containsExactly("recordings/7/a.m4a", "images/7/b.jpg", "uploads/7/loose.m4a");
    }

    @Test void singleTaskDeleteLocksActiveOwnerAndRecordsImageKeyForSecondSweep() {
        User user = new User(); user.setId(7L);
        when(users.lockById(7L)).thenReturn(user);
        RecognitionTask task = new RecognitionTask(); task.setId(42L); task.setUserId(7L);
        task.setStatus("QUEUED"); task.setOssObject("recordings/7/a.m4a");
        when(tasks.selectById(42L)).thenReturn(task);
        TaskAsset image = new TaskAsset(); image.setObjectKey("images/7/b.jpg");
        when(assets.selectByTask(42L)).thenReturn(List.of(image));
        service.deleteTask(7L, 42L);
        var order = inOrder(users, tasks);
        order.verify(users).lockById(7L);
        order.verify(tasks).selectById(42L);
        var captured = org.mockito.ArgumentCaptor.forClass(DeletionJob.class);
        verify(jobs).insert(captured.capture());
        assertThat(DeletionService.pendingObjects(captured.getValue().getRemainingObjects()))
            .containsExactly("recordings/7/a.m4a", "images/7/b.jpg");
    }

    @Test void erasedAccountCannotScheduleSingleTaskCleanup() {
        User user = new User(); user.setId(7L); user.setErasedAt(LocalDateTime.now());
        when(users.lockById(7L)).thenReturn(user);
        assertThatThrownBy(() -> service.deleteTask(7L, 42L)).isInstanceOf(BizException.class);
        verifyNoInteractions(tasks, jobs);
    }

    @Test void erasedAccountCannotScheduleNewCleanup() {
        User user = new User(); user.setId(7L); user.setErasedAt(LocalDateTime.now());
        when(users.lockById(7L)).thenReturn(user);
        assertThatThrownBy(() -> service.eraseAll(7L)).isInstanceOf(BizException.class);
        verifyNoInteractions(tasks, jobs);
    }
}
