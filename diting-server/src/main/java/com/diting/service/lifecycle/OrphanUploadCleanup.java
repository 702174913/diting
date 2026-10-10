package com.diting.service.lifecycle;

import com.diting.common.BusinessTime;
import com.diting.entity.UploadSession;
import com.diting.mapper.UploadSessionMapper;
import com.diting.service.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 与上传登记共用会话行锁，只有仍未消费、签名已失效的会话才清理。 */
@Service
public class OrphanUploadCleanup {
    private final UploadSessionMapper sessions;
    private final ObjectStorage storage;

    public OrphanUploadCleanup(UploadSessionMapper sessions, ObjectStorage storage) {
        this.sessions = sessions;
        this.storage = storage;
    }

    @Transactional
    public boolean cleanup(Long id) {
        UploadSession session = sessions.lockById(id);
        if (session == null || !("SIGNED".equals(session.getStatus()) || "VERIFIED".equals(session.getStatus()))
            || session.getCleanupAfter() == null || session.getCleanupAfter().isAfter(BusinessTime.now())
            || session.getSignedUntil() == null
            || session.getSignedUntil().plusMinutes(1).isAfter(BusinessTime.now())) {
            return false;
        }
        storage.delete(session.getObjectKey());
        return sessions.deleteById(id) == 1;
    }
}
