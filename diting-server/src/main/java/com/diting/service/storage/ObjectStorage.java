package com.diting.service.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import com.diting.common.BizException;
import com.diting.common.ResultCode;

/**
 * 对象存储端口（开发文档 §5.1）。
 *
 * 上传协议固定为「服务端签发预签名 URL + 客户端原始字节 PUT」，
 * 这个接口只负责签发与校验，不改变上传架构；
 * 具体实现按配置选择（真实 OSS / 内存实现（仅测试）/ 未配置）。
 */
public interface ObjectStorage {

    /** 签发 PUT 预签名，客户端必须严格按返回的 method 与 headers 发送 */
    PresignedRequest presignPut(String objectKey, Duration ttl, String contentType);

    /** 签发 GET 预签名（给听悟下载、导出下载用） */
    PresignedRequest presignGet(String objectKey, Duration ttl);

    /**
     * 签发 GET 预签名，并让浏览器**另存为** downloadName
     * （对应 OSS 的 response-content-disposition=attachment）。
     *
     * 导出产物必须用它：不带处置头时 PDF 会被浏览器直接内联打开，用户拿不到文件。
     * 默认实现忽略文件名（内存/未配置实现不承担真实下载语义），由真实存储实现覆盖。
     */
    default PresignedRequest presignGet(String objectKey, Duration ttl, String downloadName) {
        return presignGet(objectKey, ttl);
    }

    /** 对象元信息；对象不存在返回 null */
    ObjectMeta head(String objectKey);

    /** 读取对象开头若干字节，用于真实格式嗅探 */
    byte[] readPrefix(String objectKey, int maxBytes);

    /** 服务端直传（导出产物、报告 PDF 等由服务端生成后写入） */
    void put(String objectKey, byte[] content, String contentType);

    /** 从流上传指定长度的产物；实现方不得将整个流读入内存，调用方负责关闭流。 */
    void putStream(String objectKey, InputStream content, long length, String contentType);

    /** 读取完整对象（导出打包原件时用；调用方需自行限制体积） */
    byte[] read(String objectKey);

    /** 读取有上限的原件；即使上传后被覆盖，也不能在大小校验前无限分配内存。 */
    default byte[] readLimited(String objectKey, int maxBytes) {
        if (maxBytes < 0 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("非法读取上限");
        try (InputStream input = open(objectKey)) {
            if (input == null) return null;
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new BizException(ResultCode.UNPROCESSABLE, "原件超过大小上限");
            }
            return bytes;
        }
        catch (IOException e) {
            throw new IllegalStateException("读取对象失败", e);
        }
    }

    /** 流式读取原件，调用方负责关闭。 */
    InputStream open(String objectKey);

    void delete(String objectKey);

    record PresignedRequest(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record ObjectMeta(long size, String contentType, String etag) {
    }
}
