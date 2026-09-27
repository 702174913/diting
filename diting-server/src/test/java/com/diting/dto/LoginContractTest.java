package com.diting.dto;

import com.diting.common.BizException;
import com.diting.common.PageQuery;
import com.diting.common.PageResult;
import com.diting.common.R;
import com.diting.common.ResultCode;
import com.diting.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTH-08/09：两端契约一致性。
 * 字段名一旦变化，这里先失败，避免前后端“各自以为对方长这样”。
 */
class LoginContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setPasswordHash("$2a$10$hash");
        user.setWechatOpenid("openid_secret");
        return user;
    }

    @Test
    @DisplayName("AUTH-09-1 C 端登录结果字段与小程序 types/api.ts 一致")
    void loginResultContract() throws Exception {
        JsonNode root = mapper.readTree(mapper.writeValueAsString(
            LoginResult.of("jwt-token", user(), true)));

        assertThat(root.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("token", "userInfo", "isNewUser");
        JsonNode info = root.get("userInfo");
        assertThat(info.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "username", "nickname", "hasPassword", "hasWechat");
        assertThat(info.get("hasPassword").asBoolean()).isTrue();
        assertThat(info.get("hasWechat").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AUTH-08-2 userInfo 永不包含密码哈希或 openid")
    void userInfoHasNoSecrets() throws Exception {
        String json = mapper.writeValueAsString(UserInfo.of(user()));

        assertThat(json).doesNotContain("hash").doesNotContain("$2a$").doesNotContain("openid");
    }

    @Test
    @DisplayName("AUTH-09-2 后台登录结果字段与后台 types/api.ts 一致")
    void adminLoginResultContract() throws Exception {
        JsonNode root = mapper.readTree(mapper.writeValueAsString(
            new AdminLoginResult("jwt-token", "OPERATOR", "运维")));

        assertThat(root.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("token", "role", "nickname");
        assertThat(root.get("role").asText()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("AUTH-09-3 错误信封：code 与 HTTP 语义一致，symbol 可判分支")
    void errorEnvelope() throws Exception {
        JsonNode ok = mapper.readTree(mapper.writeValueAsString(R.ok("data")));
        assertThat(ok.get("code").asInt()).isZero();
        assertThat(ok.get("symbol").isNull()).isTrue();

        JsonNode fail = mapper.readTree(mapper.writeValueAsString(R.fail(ResultCode.LOGIN_FAILED)));
        assertThat(fail.get("code").asInt()).isEqualTo(401);
        assertThat(fail.get("symbol").asText()).isEqualTo("LOGIN_FAILED");

        assertThat(ResultCode.QUOTA_EXCEEDED.getCode()).isEqualTo(429);
        assertThat(ResultCode.ROLE_UNCONFIRMED.getCode()).isEqualTo(409);
        assertThat(ResultCode.AUDIO_EXPIRED.getCode()).isEqualTo(404);
        assertThat(ResultCode.NOT_CONFIGURED.getCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("分页契约：page 从 1 起、默认 20、上限 100，越界 422")
    void paginationContract() throws Exception {
        assertThat(PageQuery.of(null, null)).isEqualTo(new PageQuery(1, 20));
        assertThat(PageQuery.of(3, 50).offset()).isEqualTo(100L);

        assertThatThrownBy(() -> PageQuery.of(0, 20))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);
        assertThatThrownBy(() -> PageQuery.of(1, 101))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.UNPROCESSABLE);

        JsonNode page = mapper.readTree(mapper.writeValueAsString(
            PageResult.of(List.of("a"), 42L, new PageQuery(2, 20))));
        assertThat(page.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("items", "total", "page", "pageSize");
        assertThat(page.get("page").asInt()).isEqualTo(2);
        assertThat(page.get("total").asLong()).isEqualTo(42L);
    }
}
