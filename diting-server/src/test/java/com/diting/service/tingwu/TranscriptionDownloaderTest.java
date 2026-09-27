package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ASR-08 的下载侧：体积上限、重定向次数、非 2xx。
 * 本机起一个最小 HTTP 服务，策略换成「允许回环」的测试实现（生产用 Strict）。
 */
class TranscriptionDownloaderTest {

    /** 测试策略：允许 http/回环（本机服务），但重定向上限为 1 */
    private static final ResultUrlPolicy TEST_POLICY = new ResultUrlPolicy() {
        @Override
        public void assertAllowed(URI uri) {
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new Violation("只允许 http/https（测试策略）");
            }
        }

        @Override
        public int maxRedirects() {
            return 1;
        }
    };

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(String path, int status, String body, String locationHeader) {
        server.createContext(path, exchange -> {
            if (locationHeader != null) {
                exchange.getResponseHeaders().add("Location", locationHeader);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    @DisplayName("正常下载返回内容")
    void downloads() {
        respond("/ok", 200, "{\"Transcription\":{\"Paragraphs\":[]}}", null);
        TranscriptionDownloader downloader = new TranscriptionDownloader(TEST_POLICY);

        assertThat(downloader.download(base + "/ok")).contains("Paragraphs");
    }

    @Test
    @DisplayName("超过体积上限：中断并报错，不把超大结果读进内存")
    void sizeLimited() {
        respond("/big", 200, "x".repeat(4096), null);
        TranscriptionDownloader downloader = new TranscriptionDownloader(TEST_POLICY, 1024, Duration.ofSeconds(5));

        assertThatThrownBy(() -> downloader.download(base + "/big"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("体积上限");
    }

    @Test
    @DisplayName("重定向：允许一次、第二次拒绝")
    void redirectLimit() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/r1", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", base + "/r2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/r2", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/r3");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        respond("/r3", 200, "should-not-reach", null);

        TranscriptionDownloader downloader = new TranscriptionDownloader(TEST_POLICY);
        assertThatThrownBy(() -> downloader.download(base + "/r1"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("重定向次数过多");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非 2xx：明确报错并带上状态码")
    void nonSuccess() {
        respond("/missing", 404, "nope", null);
        TranscriptionDownloader downloader = new TranscriptionDownloader(TEST_POLICY);

        assertThatThrownBy(() -> downloader.download(base + "/missing"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("HTTP 404");
    }

    @Test
    @DisplayName("非法地址：被策略拒绝")
    void policyRejects() {
        TranscriptionDownloader downloader = new TranscriptionDownloader(TEST_POLICY);

        assertThatThrownBy(() -> downloader.download("file:///etc/passwd"))
            .isInstanceOf(ResultUrlPolicy.Violation.class);
    }
}
