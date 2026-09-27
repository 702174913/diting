package com.diting.dto;

import com.diting.entity.TaskAsset;

/**
 * 任务资产（S16）：音频与图片的元数据 + 短期可访问地址。
 *
 * 只回显展示所需字段：不返回对象 key（服务端生成、客户端无需知道），
 * `url` 是短期预签名 GET（默认 15 分钟），`urlExpiresAt` 给出到期时间；
 * 已过保留期或已清理的资产 `url` 为空、`purged` 为 true——界面据此显示「原件已清理」。
 */
public record AssetItemResponse(
    Long assetId,
    String kind,
    Integer sequence,
    String filename,
    String mediaFormat,
    Long sizeBytes,
    String ocrStatus,
    boolean purged,
    String url,
    String urlExpiresAt
) {

    public static AssetItemResponse of(TaskAsset asset, String url, String urlExpiresAt) {
        return new AssetItemResponse(asset.getId(), asset.getKind(), asset.getSequence(),
            asset.getFilename(), asset.getMediaFormat(), asset.getSizeBytes(),
            asset.getOcrStatus(), isPurged(asset), url, urlExpiresAt);
    }

    public static AssetItemResponse withoutUrl(TaskAsset asset) {
        return of(asset, null, null);
    }

    private static boolean isPurged(TaskAsset asset) {
        return asset.getObjectKey() == null || asset.getObjectKey().isBlank();
    }
}
