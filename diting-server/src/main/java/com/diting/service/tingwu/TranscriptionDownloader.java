package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 可信结果下载（开发文档 §5.3）：
 * 只下载听悟返回的 Result.Transcription，限制来源、体积与超时；
 * 下载地址与内容都不进日志（避免把签名链接和原文写出去）。
 */
public class TranscriptionDownloader {

    private final HttpClient client;
    private final ResultUrlPolicy policy;
    private final long maxBytes;
    private final Duration timeout;

    public TranscriptionDownloader(ResultUrlPolicy policy) {
        this(policy, 8L * 1024 * 1024, Duration.ofSeconds(15));
    }

    public TranscriptionDownloader(ResultUrlPolicy policy, long maxBytes, Duration timeout) {
        this.policy = policy;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public String download(String url) {
        URI uri = parse(url);
        int redirects = 0;
        while (true) {
            policy.assertAllowed(uri);
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                if (redirects >= policy.maxRedirects()) {
                    throw new BizException(ResultCode.UNPROCESSABLE, "结果地址重定向次数过多，已拒绝");
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new BizException(ResultCode.UNPROCESSABLE, "结果地址重定向缺少 Location");
                }
                uri = parse(uri.resolve(location).toString());
                redirects++;
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new BizException(ResultCode.UNPROCESSABLE, "下载转写结果失败（HTTP " + status + "）");
            }
            return read(response.body());
        }
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .GET()
            .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (Exception e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "下载转写结果失败：" + e.getMessage());
        }
    }

    private String read(InputStream body) {
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BizException(ResultCode.UNPROCESSABLE,
                        "转写结果超过体积上限（" + (maxBytes / 1024 / 1024) + "MB）");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
        catch (BizException e) {
            throw e;
        }
        catch (Exception e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "读取转写结果失败：" + e.getMessage());
        }
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        }
        catch (Exception e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "结果地址不合法");
        }
    }
}
