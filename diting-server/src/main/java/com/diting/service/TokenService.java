package com.diting.service;

import com.diting.common.LoginDomain;
import com.diting.config.DitingProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 自有登录态：JWT 签发与校验。
 * 每个身份域使用独立 audience 与域声明：C 端 token 拿去调后台接口、后台 token 冒充同数字 ID，
 * 都会在解析阶段就被拒。
 *
 * 兼容性说明：旧版本签发的 token 不含 aud/domain，本次改造后一律失效，
 * 客户端会收到 401 并重新登录（开发计划 S02「旧 token 失效策略」）。
 */
@Service
public class TokenService {

    static final String ISSUER = "diting-server";
    static final String DOMAIN_CLAIM = "domain";

    private final SecretKey key;
    private final long expireHours;

    public TokenService(DitingProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireHours = props.getJwt().getExpireHours();
    }

    public String issue(Long subjectId, LoginDomain domain) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(ISSUER)
            .subject(String.valueOf(subjectId))
            .audience().add(domain.getAudience()).and()
            .claim(DOMAIN_CLAIM, domain.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(Duration.ofHours(expireHours))))
            .signWith(key)
            .compact();
    }

    /**
     * 解析并校验身份域；过期、伪造、audience 不符、域声明不符一律返回 null。
     */
    public Long parse(String token, LoginDomain expected) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .requireAudience(expected.getAudience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!expected.name().equals(claims.get(DOMAIN_CLAIM, String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        }
        catch (Exception e) {
            return null;
        }
    }
}
