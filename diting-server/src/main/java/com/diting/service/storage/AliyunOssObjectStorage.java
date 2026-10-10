package com.diting.service.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.diting.config.DitingProperties;
import jakarta.annotation.PreDestroy;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 阿里云 OSS 实现：预签名 PUT/GET、HEAD 校验、前缀读取与删除 */
public class AliyunOssObjectStorage implements ObjectStorage {

    private final OSS oss;
    private final String bucket;

    public AliyunOssObjectStorage(DitingProperties props) {
        DitingProperties.Aliyun.Oss ossConfig = props.getAliyun().getOss();
        this.bucket = ossConfig.getBucket();
        // OSS 自 2025-09 起对新 Bucket 全面禁用 V1 签名（错误码 0002-00000080
        // "V1 signature is forbidden"）：预签名 PUT/GET 必须用 V4，否则上传直接 403。
        // SDK 3.17.4 默认仍是 V1。注意：不能用 build(endpoint, ak, sk, region, config)
        // 那类旧重载——它没有 region 位（第 4 参是 STS Token），V4 signer 会拿到 null region
        // 直接 NPE；V4 只能走 create().region() 这条路径。
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        this.oss = OSSClientBuilder.create()
            .endpoint(ossConfig.getEndpoint())
            .credentialsProvider(CredentialsProviderFactory.newDefaultCredentialProvider(
                props.getAliyun().getAccessKeyId(), props.getAliyun().getAccessKeySecret()))
            .clientConfiguration(config)
            .region(regionOf(ossConfig.getEndpoint()))
            .build();
    }

    /**
     * endpoint → region ID（V4 签名必需）：`https://oss-cn-qingdao.aliyuncs.com` → `cn-qingdao`。
     * 兼容内网端点（`-internal`）与协议/路径/端口前缀。非标准 endpoint（自定义域名、加速域名）
     * 无法推导时直接报错——不回退 V1，因为 V1 已被 OSS 拒绝，静默回退只会把错误推迟到上传时。
     */
    static String regionOf(String endpoint) {
        String host = endpoint == null ? "" : endpoint.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        String first = host.split("\\.")[0];
        if (first.startsWith("oss-")) {
            String region = first.substring("oss-".length());
            if (region.endsWith("-internal")) {
                region = region.substring(0, region.length() - "-internal".length());
            }
            if (!region.isBlank() && !"accelerate".equals(region)) {
                return region;
            }
        }
        throw new IllegalStateException(
            "无法从 OSS endpoint 推导 region（V4 签名必需）：" + endpoint
                + "；请改用标准 endpoint（如 https://oss-cn-qingdao.aliyuncs.com）");
    }

    @Override
    public PresignedRequest presignPut(String objectKey, Duration ttl, String contentType) {
        return presign(objectKey, HttpMethod.PUT, ttl, contentType);
    }

    @Override
    public PresignedRequest presignGet(String objectKey, Duration ttl) {
        return presign(objectKey, HttpMethod.GET, ttl, null, null);
    }

    /**
     * 导出产物下载：让浏览器直接另存为 downloadName。
     * 处置头作为签名参数（response-content-disposition）下发，因此不能事后拼接到 URL 上。
     */
    @Override
    public PresignedRequest presignGet(String objectKey, Duration ttl, String downloadName) {
        return presign(objectKey, HttpMethod.GET, ttl, null, downloadName);
    }

    private PresignedRequest presign(String objectKey, HttpMethod method, Duration ttl, String contentType) {
        return presign(objectKey, method, ttl, contentType, null);
    }

    private PresignedRequest presign(String objectKey, HttpMethod method, Duration ttl, String contentType,
                                     String downloadName) {
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, objectKey, method);
        req.setExpiration(java.util.Date.from(Instant.now().plus(ttl)));
        if (contentType != null && !contentType.isBlank()) {
            req.setContentType(contentType);
        }
        if (downloadName != null && !downloadName.isBlank()) {
            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
            overrides.setContentDisposition("attachment; filename=\"" + asciiFileName(downloadName) + "\"");
            req.setResponseHeaders(overrides);
        }
        URL url = oss.generatePresignedUrl(req);
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        return new PresignedRequest(url.toString(), method.name(), headers,
            Instant.now().plus(ttl));
    }

    /**
     * HTTP 头只能放 ISO-8859-1：文件名里的非 ASCII（中文等）换成下划线，
     * 否则某些客户端会直接把处置头当乱码或拒收。文件名由服务端生成，本身已限定为 ASCII。
     */
    private static String asciiFileName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(c < 0x20 || c > 0x7e || c == '"' || c == '\\' ? '_' : c);
        }
        return out.toString();
    }

    @Override
    public ObjectMeta head(String objectKey) {
        if (!oss.doesObjectExist(bucket, objectKey)) {
            return null;
        }
        ObjectMetadata meta = oss.getObjectMetadata(bucket, objectKey);
        return new ObjectMeta(meta.getContentLength(), meta.getContentType(), meta.getETag());
    }

    @Override
    public byte[] readPrefix(String objectKey, int maxBytes) {
        try (OSSObject object = oss.getObject(bucket, objectKey)) {
            try (InputStream in = object.getObjectContent()) {
                return in.readNBytes(maxBytes);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("读取对象前缀失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        oss.putObject(bucket, objectKey, new java.io.ByteArrayInputStream(content), metadata);
    }

    @Override
    public void putStream(String objectKey, InputStream content, long length, String contentType) {
        com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength(length);
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        oss.putObject(bucket, objectKey, content, metadata);
    }

    @Override
    public byte[] read(String objectKey) {
        try (OSSObject object = oss.getObject(bucket, objectKey);
             InputStream in = object.getObjectContent()) {
            return in.readAllBytes();
        }
        catch (Exception e) {
            throw new IllegalStateException("读取对象失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectKey) {
        oss.deleteObject(bucket, objectKey);
    }

    @Override
    public InputStream open(String objectKey) {
        OSSObject object = oss.getObject(bucket, objectKey);
        return object == null ? null : object.getObjectContent();
    }

    @PreDestroy
    void shutdown() {
        oss.shutdown();
    }
}
