package com.diting.service.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存对象存储：**仅测试使用**（`diting.storage.type=memory`）。
 * 让上传协议、归属校验、格式/大小验证能在没有云凭据的前提下被完整验收，
 * 生产不会启用它：默认按凭据选择 OSS，缺凭据得到 NOT_CONFIGURED。
 */
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final String baseUrl;

    public InMemoryObjectStorage(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 测试辅助：模拟客户端把对象 PUT 上去（生产里由 OSS 接收） */
    public void putObject(String objectKey, byte[] content, String contentType) {
        objects.put(objectKey, new StoredObject(content.clone(), contentType));
    }

    public boolean exists(String objectKey) {
        return objects.containsKey(objectKey);
    }

    @Override
    public PresignedRequest presignPut(String objectKey, Duration ttl, String contentType) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        return new PresignedRequest(url(objectKey), "PUT", headers, Instant.now().plus(ttl));
    }

    @Override
    public PresignedRequest presignGet(String objectKey, Duration ttl) {
        return new PresignedRequest(url(objectKey), "GET", Map.of(), Instant.now().plus(ttl));
    }

    @Override
    public ObjectMeta head(String objectKey) {
        StoredObject object = objects.get(objectKey);
        return object == null ? null
            : new ObjectMeta(object.content().length, object.contentType(), etag(object.content()));
    }

    @Override
    public byte[] readPrefix(String objectKey, int maxBytes) {
        StoredObject object = objects.get(objectKey);
        if (object == null) {
            return new byte[0];
        }
        int length = Math.min(maxBytes, object.content().length);
        byte[] head = new byte[length];
        System.arraycopy(object.content(), 0, head, 0, length);
        return head;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        putObject(objectKey, content, contentType);
    }

    @Override
    public byte[] read(String objectKey) {
        StoredObject object = objects.get(objectKey);
        return object == null ? null : object.content().clone();
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }

    private String url(String objectKey) {
        return baseUrl + "/" + objectKey;
    }

    private static String etag(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private record StoredObject(byte[] content, String contentType) {
    }
}
