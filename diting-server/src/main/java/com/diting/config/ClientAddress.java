package com.diting.config;

import jakarta.servlet.http.HttpServletRequest;

/** 限流用的来源地址：不信任客户端可直接设置的转发头。 */
public final class ClientAddress {
    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
