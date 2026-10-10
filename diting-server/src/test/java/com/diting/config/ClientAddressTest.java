package com.diting.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressTest {
    @Test void ignoresUntrustedForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.2");
        request.addHeader("X-Forwarded-For", "198.51.100.8");
        assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.2");
    }
}
