package com.diting.service.storage;

import com.diting.common.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStorageLimitedReadTest {
    @Test void rejectsOverwrittenObjectBeforeReadingAllBytes() {
        InMemoryObjectStorage storage = new InMemoryObjectStorage("http://localhost");
        storage.putObject("images/a.jpg", new byte[1024 * 1024], "image/jpeg");

        assertThatThrownBy(() -> storage.readLimited("images/a.jpg", 1024))
            .isInstanceOf(BizException.class).hasMessageContaining("大小上限");
        assertThat(storage.readLimited("images/a.jpg", 1024 * 1024)).hasSize(1024 * 1024);
        assertThat(storage.readLimited("images/missing", 1024)).isNull();
    }
}
