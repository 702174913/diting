package com.diting.service.tingwu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunTingwuApiTest {

    @Test
    void genericCallResponseUnwrapsBodyBeforeReadingTaskId() {
        Map<String, ?> response = Map.of(
            "statusCode", 200,
            "headers", Map.of("x-acs-request-id", "request-1"),
            "body", Map.of("Code", "0", "Data", Map.of("TaskId", "tw-123")));

        assertThat(AliyunTingwuApi.responseBody(response)).containsKey("Data");
        assertThat(AliyunTingwuApi.responseBody(response).get("Code")).isEqualTo("0");
        assertThat(JsonNodeReader.of(AliyunTingwuApi.responseBody(response)).text("Data.TaskId"))
            .isEqualTo("tw-123");
    }

    @Test
    void getTaskParamsPassSdkValidationBeforeRemotePolling() {
        var params = AliyunTingwuApi.getTaskParams("tw-123");
        assertThat(params.getMethod()).isEqualTo("GET");
        assertThat(params.getPathname()).endsWith("/tw-123");
        assertThat(params.getBodyType()).isEqualTo("json");
        params.validate();
    }

    @Test
    void missingBodyIsNotMistakenForEmptyBusinessResponse() {
        assertThatThrownBy(() -> AliyunTingwuApi.responseBody(Map.of("statusCode", 200)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JSON body");
    }
}
