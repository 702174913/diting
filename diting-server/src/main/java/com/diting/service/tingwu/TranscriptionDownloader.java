package com.diting.service.tingwu;

import com.diting.common.BizException;
import com.diting.common.ResultCode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * 可信结果下载（开发文档 §5.3）：
 * 只下载听悟返回的 Result.Transcription，限制来源、体积与超时；
 * 下载地址与内容都不进日志（避免把签名链接和原文写出去）。
 */
public class TranscriptionDownloader {

    // 系统 DNS 解析不保证响应线程中断；有界线程池防止卡住的解析耗尽请求线程或堆积任务。
    private static final ThreadPoolExecutor RESOLVERS = new ThreadPoolExecutor(0, 4, 30,
        TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread thread = new Thread(r, "tingwu-result-dns");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tingwu-download-deadline");
        thread.setDaemon(true);
        return thread;
    });

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
    }

    public String download(String url) {
        URI uri = parse(url);
        int redirects = 0;
        long deadline = System.nanoTime() + timeout.toNanos();
        AtomicReference<HttpGet> active = new AtomicReference<>();
        var cancellation = DEADLINES.schedule(() -> {
            HttpGet request = active.get();
            if (request != null) request.cancel();
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            while (true) {
                checkDeadline(deadline);
                InetAddress[] addresses = resolveWithDeadline(uri, deadline);
                checkDeadline(deadline);
                String checkedHost = uri.getHost();
                DnsResolver pinnedDns = new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) throws UnknownHostException {
                        if (!host.equalsIgnoreCase(checkedHost)) {
                            throw new UnknownHostException(host);
                        }
                        return addresses;
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) {
                        return host;
                    }
                };
                HttpGet request = new HttpGet(uri);
                active.set(request);
                try (var manager = PoolingHttpClientConnectionManagerBuilder.create()
                         .setDnsResolver(pinnedDns)
                         .setDefaultConnectionConfig(ConnectionConfig.custom()
                             .setConnectTimeout(Timeout.ofMilliseconds(Math.min(5000, remainingMillis(deadline))))
                             .build()).build();
                     var client = HttpClients.custom().setConnectionManager(manager)
                         .setDefaultRequestConfig(RequestConfig.custom()
                             .setResponseTimeout(Timeout.ofMilliseconds(remainingMillis(deadline))).build())
                         .disableRedirectHandling().disableAutomaticRetries().build();
                     var response = client.executeOpen(null, request, null)) {
                    checkDeadline(deadline);
                    int status = response.getCode();
                    if (status >= 300 && status < 400) {
                        if (redirects >= policy.maxRedirects()) {
                            throw new BizException(ResultCode.UNPROCESSABLE, "结果地址重定向次数过多，已拒绝");
                        }
                        String location = response.getFirstHeader("Location") == null ? null
                            : response.getFirstHeader("Location").getValue();
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
                    if (response.getEntity() == null) {
                        throw new BizException(ResultCode.UNPROCESSABLE, "转写结果为空");
                    }
                    return read(response.getEntity().getContent(), deadline);
                }
                catch (BizException e) {
                    throw e;
                }
                catch (Exception e) {
                    checkDeadline(deadline);
                    throw new BizException(ResultCode.UNPROCESSABLE, "下载转写结果失败：" + e.getClass().getSimpleName());
                }
                finally {
                    active.compareAndSet(request, null);
                }
            }
        }
        finally {
            cancellation.cancel(false);
        }
    }

    private static long remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new BizException(ResultCode.UNPROCESSABLE, "下载转写结果超时");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private InetAddress[] resolveWithDeadline(URI uri, long deadline) {
        // URL 校验和 DNS 都放入受限任务；解析库若卡死也不能无限阻塞下载调用者。
        Future<InetAddress[]> resolution;
        try {
            resolution = RESOLVERS.submit(() -> policy.resolveAllowed(uri));
        }
        catch (RejectedExecutionException e) {
            throw new BizException(ResultCode.UNPROCESSABLE, "结果地址解析服务繁忙，请稍后重试");
        }
        try {
            return resolution.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
        }
        catch (BizException e) {
            resolution.cancel(true);
            throw e;
        }
        catch (TimeoutException e) {
            resolution.cancel(true);
            throw new BizException(ResultCode.UNPROCESSABLE, "下载转写结果超时");
        }
        catch (InterruptedException e) {
            resolution.cancel(true);
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.UNPROCESSABLE, "结果地址解析被中断");
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new BizException(ResultCode.UNPROCESSABLE, "结果地址解析失败");
        }
    }

    private static void checkDeadline(long deadline) {
        remainingMillis(deadline);
    }

    private String read(InputStream body, long deadline) {
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkDeadline(deadline);
                total += read;
                if (total > maxBytes) {
                    throw new BizException(ResultCode.UNPROCESSABLE,
                        "转写结果超过体积上限（" + (maxBytes / 1024 / 1024) + "MB）");
                }
                out.write(buffer, 0, read);
            }
            checkDeadline(deadline);
            return out.toString(StandardCharsets.UTF_8);
        }
        catch (BizException e) {
            throw e;
        }
        catch (Exception e) {
            checkDeadline(deadline);
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
