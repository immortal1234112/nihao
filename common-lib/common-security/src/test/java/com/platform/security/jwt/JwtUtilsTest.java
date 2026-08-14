package com.platform.security.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    private static final String SECRET = "test-secret-key-0123456789abcdefghijklmnopqrstuv";

    private JwtUtils jwtUtils;
    private JwtProperties properties;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenTtl(7200);
        properties.setRefreshTokenTtl(604800);
        jwtUtils = new JwtUtils(properties);
    }

    @Test
    void generateAndParse_roundTrip() {
        String token = jwtUtils.generateAccessToken("u1", "immortal", "t1", List.of("SUPER_ADMIN", "TENANT_ADMIN"));
        Claims claims = jwtUtils.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("u1");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.get("username", String.class)).isEqualTo("immortal");
        assertThat(claims.get("tenantId", String.class)).isEqualTo("t1");
        assertThat(claims.get("roles", String.class)).isEqualTo("SUPER_ADMIN,TENANT_ADMIN");
        assertThat(claims.get("jti", String.class)).isNotBlank();
        assertThat(claims.getExpiration()).isAfter(java.util.Date.from(java.time.Instant.now().plusSeconds(7000)));
    }

    @Test
    void refreshToken_hasRefreshTypeAndJti() {
        String token = jwtUtils.generateRefreshToken("u1");
        Claims claims = jwtUtils.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("u1");
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.get("jti", String.class)).isNotBlank();
    }

    @Test
    void accessToken_jtiUniquePerToken() {
        String t1 = jwtUtils.generateAccessToken("u1", "n", "t", List.of());
        String t2 = jwtUtils.generateAccessToken("u1", "n", "t", List.of());
        assertThat(jwtUtils.getJti(t1)).isNotEqualTo(jwtUtils.getJti(t2));
    }

    @Test
    void validateToken_valid_returnsTrue() {
        String token = jwtUtils.generateAccessToken("u1", "n", "t", List.of());
        assertThat(jwtUtils.validateToken(token)).isTrue();
        assertThat(jwtUtils.getUserId(token)).isEqualTo("u1");
    }

    @Test
    void validateToken_garbage_returnsFalse() {
        assertThat(jwtUtils.validateToken("not-a-token")).isFalse();
    }

    @Test
    void expiredToken_rejected() {
        // ttl 为负 → 签发即过期
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(SECRET);
        expiredProps.setAccessTokenTtl(-10);
        JwtUtils expiredUtils = new JwtUtils(expiredProps);

        String token = expiredUtils.generateAccessToken("u1", "n", "t", List.of());
        assertThat(expiredUtils.validateToken(token)).isFalse();
        assertThatThrownBy(() -> expiredUtils.parseClaims(token))
                .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }

    @Test
    void weakSecret_rejectedAtConstruction() {
        // 空密钥
        JwtProperties empty = new JwtProperties();
        empty.setSecret("");
        assertThatThrownBy(() -> new JwtUtils(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥未配置");

        // 过短密钥
        JwtProperties shortSecret = new JwtProperties();
        shortSecret.setSecret("short");
        assertThatThrownBy(() -> new JwtUtils(shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥过弱");

        // 默认占位串
        JwtProperties placeholder = new JwtProperties();
        placeholder.setSecret("change-me-in-production-min-32-chars");
        assertThatThrownBy(() -> new JwtUtils(placeholder))
                .isInstanceOf(IllegalStateException.class);
    }
}
