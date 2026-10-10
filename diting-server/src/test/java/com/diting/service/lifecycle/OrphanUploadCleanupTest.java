package com.diting.service.lifecycle;

import com.diting.common.BusinessTime;
import com.diting.entity.UploadSession;
import com.diting.mapper.UploadSessionMapper;
import com.diting.service.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrphanUploadCleanupTest {
    private final UploadSessionMapper sessions = mock(UploadSessionMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final OrphanUploadCleanup cleanup = new OrphanUploadCleanup(sessions, storage);

    private UploadSession session(String status, int signedMinutesAgo) {
        UploadSession value = new UploadSession();
        value.setId(12L);
        value.setObjectKey("recordings/1/test.m4a");
        value.setStatus(status);
        value.setSignedUntil(BusinessTime.now().minusMinutes(signedMinutesAgo));
        value.setCleanupAfter(BusinessTime.now().minusMinutes(2));
        when(sessions.lockById(12L)).thenReturn(value);
        return value;
    }

    @Test void consumedAfterCandidateSelectionIsNeverDeleted() {
        session("CONSUMED", 10);
        assertThat(cleanup.cleanup(12L)).isFalse();
        verifyNoInteractions(storage);
        verify(sessions, never()).deleteById(12L);
    }

    @Test void stillValidSignatureIsNeverDeleted() {
        session("SIGNED", 0);
        assertThat(cleanup.cleanup(12L)).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void expiredOrphanIsDeletedUnderLock() {
        session("VERIFIED", 10);
        when(sessions.deleteById(12L)).thenReturn(1);
        assertThat(cleanup.cleanup(12L)).isTrue();
        var ordered = inOrder(sessions, storage);
        ordered.verify(sessions).lockById(12L);
        ordered.verify(storage).delete("recordings/1/test.m4a");
        ordered.verify(sessions).deleteById(12L);
    }
}
