package com.diting.service;

import com.diting.common.LoginDomain;
import com.diting.config.DitingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-02：token 签发与校验。
 * 覆盖过期、伪造、跨身份域（AUTH-04/AUTH-05 的判定基础）。
 */
class TokenServiceTest {

    private static final String SECRET = "test-secret-key-for-unit-tests-only-0123456789";

    private static TokenService service(String secret, long expireHours) {
        DitingProperties props = new DitingProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setExpireHours(expireHours);
        return new TokenService(props);
    }

    @Test
    @DisplayName("AUTH-02-1 正常签发后可按同域解析出 subject")
    void issueAndParse() {
        TokenService tokens = service(SECRET, 72);
        String token = tokens.issue(42L, LoginDomain.USER);

        assertThat(tokens.parse(token, LoginDomain.USER)).isEqualTo(42L);
        assertThat(tokens.parse(token, LoginDomain.ADMIN)).isNull();
    }

    @Test
    @DisplayName("AUTH-05-1 同数字 ID 的两个身份域 token 不可互用")
    void domainsAreIsolated() {
        TokenService tokens = service(SECRET, 72);
        String userToken = tokens.issue(7L, LoginDomain.USER);
        String adminToken = tokens.issue(7L, LoginDomain.ADMIN);

        assertThat(userToken).isNotEqualTo(adminToken);
        assertThat(tokens.parse(userToken, LoginDomain.ADMIN)).isNull();
        assertThat(tokens.parse(adminToken, LoginDomain.USER)).isNull();
        assertThat(tokens.parse(userToken, LoginDomain.USER)).isEqualTo(7L);
        assertThat(tokens.parse(adminToken, LoginDomain.ADMIN)).isEqualTo(7L);
    }

    @Test
    @DisplayName("AUTH-02-2 过期 token 解析为 null")
    void expiredToken() {
        TokenService tokens = service(SECRET, -1);
        String token = tokens.issue(1L, LoginDomain.USER);

        assertThat(tokens.parse(token, LoginDomain.USER)).isNull();
    }

    @Test
    @DisplayName("AUTH-02-3 换密钥签发的 token 视为伪造")
    void forgedByOtherKey() {
        TokenService issuer = service(SECRET, 72);
        TokenService verifier = service(SECRET + "-different", 72);
        String foreign = issuer.issue(9L, LoginDomain.USER);

        assertThat(verifier.parse(foreign, LoginDomain.USER)).isNull();
        assertThat(issuer.parse(foreign, LoginDomain.USER)).isEqualTo(9L);
    }

    @Test
    @DisplayName("AUTH-02-4 被篡改的 token 解析为 null")
    void tamperedToken() {
        TokenService tokens = service(SECRET, 72);
        String token = tokens.issue(5L, LoginDomain.USER);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThat(tokens.parse(tampered, LoginDomain.USER)).isNull();
    }

    @Test
    @DisplayName("AUTH-02-5 非 JWT 文本与空值一律拒绝")
    void malformedInput() {
        TokenService tokens = service(SECRET, 72);

        assertThat(tokens.parse("not-a-jwt", LoginDomain.USER)).isNull();
        assertThat(tokens.parse("", LoginDomain.USER)).isNull();
        assertThat(tokens.parse(null, LoginDomain.USER)).isNull();
    }
}
