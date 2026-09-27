package com.diting.service.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址脱敏（路号级口径，EXPORT-06 验收样本的一部分）。
 *
 * 口径：省 / 市 / 区县镇乡保留可读，从路街巷及门牌详情开始涂；
 * 没有省市前缀的裸地址整段涂；全程等长掩码，高亮区间不移位。
 * 姓名不在此列：无名单猜名字会误杀（见 RedactionService 类注释）。
 */
class RedactionServiceTest {

    @Test
    @DisplayName("完整地址：保留到区县，从路名开始涂，等长")
    void fullAddressKeepsCoarsePart() {
        String original = "我住北京市朝阳区建国路88号301室。";
        String redacted = RedactionService.redact(original);

        assertThat(redacted).hasSameSizeAs(original);
        assertThat(redacted).startsWith("我住北京市朝阳区");
        assertThat(redacted).doesNotContain("建国路88号301室");
        assertThat(RedactionService.changed(original, redacted)).isTrue();
    }

    @Test
    @DisplayName("区县直跟门牌号：保留区县，只涂号码")
    void districtWithBareNumber() {
        String redacted = RedactionService.redact("朝阳区88号，你过来吧。");

        assertThat(redacted).startsWith("朝阳区");
        assertThat(redacted).doesNotContain("88号");
        assertThat(redacted).endsWith("，你过来吧。");
    }

    @Test
    @DisplayName("裸路名门牌：整段涂，但不吞掉前面的介词")
    void bareRoadMaskedWhole() {
        String redacted = RedactionService.redact("到建国路88号找我。");

        assertThat(redacted).startsWith("到");
        assertThat(redacted).doesNotContain("建国路88号");
        assertThat(redacted).endsWith("找我。");
    }

    @Test
    @DisplayName("裸楼栋单元室：整段涂")
    void bareUnitMaskedWhole() {
        String redacted = RedactionService.redact("3栋2单元501室是我家。");

        assertThat(redacted).doesNotContain("3栋2单元501室");
        assertThat(redacted).endsWith("是我家。");
    }

    @Test
    @DisplayName("非地址不误杀：室温、走路、普通句子原样返回")
    void nonAddressUntouched() {
        assertThat(RedactionService.redact("室温25度很舒适，走路去挺近的。"))
            .isEqualTo("室温25度很舒适，走路去挺近的。");
        assertThat(RedactionService.redact("欠的钱要还，这句话没问题。"))
            .isEqualTo("欠的钱要还，这句话没问题。");
    }

    @Test
    @DisplayName("地址与手机同句：两处都脱敏且等长")
    void addressAndPhoneTogether() {
        String original = "我住北京市朝阳区建国路88号，电话13812345678。";
        String redacted = RedactionService.redact(original);

        assertThat(redacted).hasSameSizeAs(original);
        assertThat(redacted).doesNotContain("建国路88号").doesNotContain("13812345678");
        assertThat(redacted).contains("138****5678");
    }
}
