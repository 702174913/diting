package com.diting.service.storage;

import com.diting.config.DitingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * region 推导与 V4 签名（OSS 对新 Bucket 已禁用 V1 签名，
 * 预签名必须走 V4 + 显式 region）；这里只做离线断言，不发起网络请求。
 */
class AliyunOssObjectStorageTest {

    private static DitingProperties props(String endpoint, String bucket) {
        DitingProperties props = new DitingProperties();
        DitingProperties.Aliyun aliyun = new DitingProperties.Aliyun();
        aliyun.setAccessKeyId("test-ak");
        aliyun.setAccessKeySecret("test-sk");
        DitingProperties.Aliyun.Oss oss = new DitingProperties.Aliyun.Oss();
        oss.setEndpoint(endpoint);
        oss.setBucket(bucket);
        aliyun.setOss(oss);
        props.setAliyun(aliyun);
        return props;
    }

    @Test
    @DisplayName("预签名 PUT/GET 走 V4 签名（V1 已被新 Bucket 拒绝）")
    void presignUsesV4Signature() {
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(
            props("https://oss-cn-qingdao.aliyuncs.com", "testtestss"));

        ObjectStorage.PresignedRequest put =
            storage.presignPut("uploads/1/audio/abc.m4a", Duration.ofMinutes(15), "audio/mp4");
        assertThat(put.url())
            .as("PUT 预签名必须是 V4（V1 会返回 0002-00000080 V1 signature is forbidden）")
            .contains("x-oss-signature-version=OSS4-HMAC-SHA256")
            .doesNotContain("OSSAccessKeyId=");

        ObjectStorage.PresignedRequest get =
            storage.presignGet("uploads/1/audio/abc.m4a", Duration.ofMinutes(15));
        assertThat(get.url()).contains("x-oss-signature-version=OSS4-HMAC-SHA256");

        storage.shutdown();
    }

    @Test
    @DisplayName("标准公网 endpoint：oss-cn-qingdao → cn-qingdao，带协议与斜杠也认")
    void derivesRegionFromStandardEndpoint() {
        assertThat(AliyunOssObjectStorage.regionOf("oss-cn-qingdao.aliyuncs.com")).isEqualTo("cn-qingdao");
        assertThat(AliyunOssObjectStorage.regionOf("https://oss-cn-qingdao.aliyuncs.com")).isEqualTo("cn-qingdao");
        assertThat(AliyunOssObjectStorage.regionOf("https://oss-cn-hangzhou.aliyuncs.com/")).isEqualTo("cn-hangzhou");
        assertThat(AliyunOssObjectStorage.regionOf("https://oss-cn-beijing.aliyuncs.com:443"))
            .isEqualTo("cn-beijing");
        assertThat(AliyunOssObjectStorage.regionOf("  oss-cn-guangzhou.aliyuncs.com  ")).isEqualTo("cn-guangzhou");
    }

    @Test
    @DisplayName("内网 endpoint：oss-cn-qingdao-internal → cn-qingdao")
    void derivesRegionFromInternalEndpoint() {
        assertThat(AliyunOssObjectStorage.regionOf("https://oss-cn-qingdao-internal.aliyuncs.com"))
            .isEqualTo("cn-qingdao");
    }

    @Test
    @DisplayName("非标准 endpoint 直接报错，不回退 V1（V1 已被 OSS 拒绝）")
    void rejectsNonStandardEndpoint() {
        assertThatThrownBy(() -> AliyunOssObjectStorage.regionOf("https://storage.example.com"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AliyunOssObjectStorage.regionOf("https://oss-accelerate.aliyuncs.com"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AliyunOssObjectStorage.regionOf(""))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AliyunOssObjectStorage.regionOf(null))
            .isInstanceOf(IllegalStateException.class);
    }
}
