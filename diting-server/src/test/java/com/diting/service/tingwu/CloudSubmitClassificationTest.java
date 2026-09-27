package com.diting.service.tingwu;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CloudSubmitClassificationTest {
    @Test void onlyExplicitPermanentClientRejectionsAreDefinite() {
        for (int code : new int[]{400, 401, 403, 404, 422}) {
            assertThat(AliyunTingwuApi.definitelyRejected(code)).as("HTTP %d", code).isTrue();
        }
        for (int code : new int[]{408, 409, 425, 429, 500, 502, 503}) {
            assertThat(AliyunTingwuApi.definitelyRejected(code)).as("HTTP %d", code).isFalse();
        }
        assertThat(AliyunTingwuApi.definitelyRejected(null)).isFalse();
    }
}
