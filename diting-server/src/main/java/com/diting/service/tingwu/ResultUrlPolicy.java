package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 结果下载地址策略（开发文档 §5.3）：
 * 只允许可信 HTTPS 来源，拒绝内网/回环/本地地址与带凭据的 URL，限制重定向次数。
 * 这样即使签名链接被替换成内网地址，也不能把我们变成内网探测器。
 */
public interface ResultUrlPolicy {

    /** 不满足时抛异常；调用方不得忽略 */
    void assertAllowed(URI uri);

    /** 允许的最大重定向次数：每一跳都要重新过策略 */
    default int maxRedirects() {
        return 0;
    }

    class Violation extends BizException {
        public Violation(String message) {
            super(ResultCode.UNPROCESSABLE, message);
        }
    }

    /** 严格实现：生产使用 */
    class Strict implements ResultUrlPolicy {

        private static final int MAX_REDIRECTS = 2;

        /** 解析器可注入，便于在没有 DNS 的环境里测试私网判定 */
        private final java.util.function.Function<String, InetAddress[]> resolver;
        /** 允许 http 的域名后缀（官方结果链接可能是 http；只对受信任域名放开） */
        private final List<String> httpAllowedSuffixes;

        public Strict() {
            this(Strict::resolveByDns, List.of());
        }

        public Strict(List<String> httpAllowedSuffixes) {
            this(Strict::resolveByDns, httpAllowedSuffixes);
        }

        Strict(java.util.function.Function<String, InetAddress[]> resolver) {
            this(resolver, List.of());
        }

        Strict(java.util.function.Function<String, InetAddress[]> resolver,
               List<String> httpAllowedSuffixes) {
            this.resolver = resolver;
            this.httpAllowedSuffixes = httpAllowedSuffixes == null ? List.of() : httpAllowedSuffixes;
        }

        @Override
        public void assertAllowed(URI uri) {
            if (uri == null || uri.getScheme() == null) {
                throw new Violation("结果地址不合法");
            }
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!https && !("http".equalsIgnoreCase(uri.getScheme()) && isTrustedHttpHost(uri.getHost()))) {
                throw new Violation("结果地址必须是 HTTPS（http 仅允许受信任的云存储域名）");
            }
            if (uri.getUserInfo() != null) {
                throw new Violation("结果地址不允许携带凭据");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new Violation("结果地址缺少主机名");
            }
            if (isForbiddenHost(host)) {
                throw new Violation("结果地址指向内网或本机，已拒绝：" + host);
            }
            for (InetAddress address : resolver.apply(host)) {
                // 注意：JDK 的 isSiteLocalAddress 只覆盖 IPv6 的 fec0::/10（已废弃），
                // fc00::/7（唯一本地地址）要自己判，否则私网 IPv6 会被放行。
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress() || isUniqueLocal(address)) {
                    throw new Violation("结果地址解析到内网或本机：" + address.getHostAddress());
                }
            }
        }

        @Override
        public int maxRedirects() {
            return MAX_REDIRECTS;
        }

        /** IPv6 唯一本地地址 fc00::/7 */
        private static boolean isUniqueLocal(InetAddress address) {
            return address instanceof java.net.Inet6Address
                && (address.getAddress()[0] & 0xFE) == 0xFC;
        }

        private boolean isTrustedHttpHost(String host) {
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            return httpAllowedSuffixes.stream()
                .map(suffix -> suffix.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(suffix -> !suffix.isEmpty())
                .anyMatch(lower::endsWith);
        }

        private static boolean isForbiddenHost(String host) {
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            return lower.equals("localhost") || lower.endsWith(".localhost")
                || lower.equals("metadata") || lower.endsWith(".internal");
        }

        private static InetAddress[] resolveByDns(String host) {
            try {
                return InetAddress.getAllByName(host);
            }
            catch (UnknownHostException e) {
                throw new Violation("结果地址无法解析：" + host);
            }
        }
    }
}
