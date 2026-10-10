package com.diting.service.tingwu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ASR-08：结果下载地址策略。
 * 用注入的解析器避免依赖 DNS，同时覆盖「字面私网地址」与「解析到私网」两条路径。
 */
class ResultUrlPolicyTest {

    /** 解析器桩：所有主机名都解析为公网地址，只有字面 IP 才按原样返回 */
    private static ResultUrlPolicy.Strict policyResolvingTo(String address) {
        return new ResultUrlPolicy.Strict(host -> {
            try {
                return new InetAddress[]{InetAddress.getByName(address)};
            }
            catch (UnknownHostException e) {
                throw new ResultUrlPolicy.Violation("无法解析：" + host);
            }
        });
    }

    private static final ResultUrlPolicy.Strict PUBLIC = policyResolvingTo("93.184.216.34");

    @ParameterizedTest
    @DisplayName("拒绝非 HTTPS 与携带凭据的地址")
    @ValueSource(strings = {
        "http://tingwu-result.example.com/a.json",
        "ftp://tingwu-result.example.com/a.json",
        "https://user:pass@tingwu-result.example.com/a.json"
    })
    void rejectsInsecureSchemes(String url) {
        assertThatThrownBy(() -> PUBLIC.assertAllowed(URI.create(url)))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @ParameterizedTest
    @DisplayName("拒绝内网/本机主机名")
    @ValueSource(strings = {
        "https://localhost/a.json",
        "https://metadata/a.json",
        "https://oss.internal/a.json"
    })
    void rejectsInternalHostNames(String url) {
        assertThatThrownBy(() -> PUBLIC.assertAllowed(URI.create(url)))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @ParameterizedTest
    @DisplayName("解析到回环/私网/链路本地/组播一律拒绝（即使主机名看起来正常）")
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "192.168.1.10", "172.16.5.5",
        "169.254.169.254", "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1"
    })
    void rejectsPrivateAddresses(String address) {
        ResultUrlPolicy.Strict strict = policyResolvingTo(address);

        assertThatThrownBy(() -> strict.assertAllowed(URI.create("https://tingwu-result.example.com/a.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @Test
    @DisplayName("受信任云存储域名可走 http，其它域名仍必须 https；私网检测不受影响")
    void httpOnlyForTrustedHosts() {
        List<String> trusted = List.of("aliyuncs.com");
        // 解析器桩：受信任域名解析到公网地址；私网地址仍应被拒
        ResultUrlPolicy.Strict policy = new ResultUrlPolicy.Strict(
            host -> {
                try {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                catch (UnknownHostException e) {
                    throw new ResultUrlPolicy.Violation("无法解析：" + host);
                }
            }, trusted);

        assertThatCode(() -> policy.assertAllowed(
            URI.create("http://speech-swap.oss-cn-zhangjiakou.aliyuncs.com/tingwu/x.json")))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertAllowed(URI.create("http://evil.example.com/x.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class)
            .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> policy.assertAllowed(URI.create("http://notaliyuncs.com/x.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
        assertThatCode(() -> policy.assertAllowed(URI.create("http://aliyuncs.com/x.json")))
            .doesNotThrowAnyException();

        // 受信任域名 + 私网解析：仍要拒绝（防 SSRF）
        ResultUrlPolicy.Strict privateHost = new ResultUrlPolicy.Strict(
            host -> new InetAddress[]{java.net.InetAddress.getLoopbackAddress()}, trusted);
        assertThatThrownBy(() -> privateHost.assertAllowed(
            URI.create("http://oss.aliyuncs.com/x.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class)
            .hasMessageContaining("内网");

        ResultUrlPolicy.Strict dotted = new ResultUrlPolicy.Strict(
            host -> new InetAddress[]{InetAddress.getLoopbackAddress()}, List.of(".aliyuncs.com"));
        assertThatThrownBy(() -> dotted.assertAllowed(URI.create("http://oss.aliyuncs.com/x.json")))
            .hasMessageContaining("内网");
    }

    @Test
    @DisplayName("合法 HTTPS 公网地址放行；重定向次数上限为 2")
    void allowsPublicHttps() {
        assertThatCode(() -> PUBLIC.assertAllowed(
            URI.create("https://tingwu-result.example.com/a.json?sig=stub"))).doesNotThrowAnyException();
        assertThat(PUBLIC.maxRedirects()).isEqualTo(2);
    }

    @Test
    @DisplayName("解析失败视为不可信：拒绝而不是放行")
    void unresolvableIsRejected() {
        ResultUrlPolicy.Strict strict = new ResultUrlPolicy.Strict(host -> {
            throw new ResultUrlPolicy.Violation("无法解析：" + host);
        });

        assertThatThrownBy(() -> strict.assertAllowed(URI.create("https://no-such-host.example/a.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @Test
    @DisplayName("空地址与缺少主机名被拒")
    void malformedUrls() {
        assertThatThrownBy(() -> PUBLIC.assertAllowed(null)).isInstanceOf(ResultUrlPolicy.Violation.class);
        assertThatThrownBy(() -> PUBLIC.assertAllowed(URI.create("/relative/path")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @Test
    @DisplayName("默认构造仍走真实 DNS 解析（生产行为）")
    void defaultConstructorResolvesByDns() {
        ResultUrlPolicy.Strict strict = new ResultUrlPolicy.Strict();

        assertThatThrownBy(() -> strict.assertAllowed(URI.create("https://localhost/a.json")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }

    @Test
    @DisplayName("策略是接口：可替换（下载器测试用宽松实现）")
    void interfaceIsReplaceable() {
        ResultUrlPolicy permissive = new ResultUrlPolicy() {
            @Override
            public void assertAllowed(URI uri) {
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                    throw new Violation("只允许 http/https（测试策略）");
                }
            }

            @Override
            public InetAddress[] resolveAllowed(URI uri) {
                assertAllowed(uri);
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }

            @Override
            public int maxRedirects() {
                return 1;
            }
        };

        assertThatCode(() -> permissive.assertAllowed(URI.create("http://127.0.0.1:9/a.json")))
            .doesNotThrowAnyException();
        assertThat(permissive.maxRedirects()).isEqualTo(1);
        assertThatThrownBy(() -> permissive.assertAllowed(URI.create("file:///etc/passwd")))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }
}
