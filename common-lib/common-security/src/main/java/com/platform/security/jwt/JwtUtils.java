package com.platform.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtils {

    private final JwtProperties jwtProperties;

    public JwtUtils(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // 拒绝弱密钥启动:空值、默认占位串或长度不足 —— 防止 token 可被离线伪造
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置:必须通过环境变量 JWT_SECRET 设置(≥32 字符)");
        }
        if (secret.length() < 32 || "change-me-in-production-min-32-chars".equals(secret)) {
            throw new IllegalStateException("JWT 密钥过弱:请设置 JWT_SECRET 为 ≥32 字符的强随机串");
        }
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String userId, String username, String tenantId, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("type", "access")
                .claim("username", username)
                .claim("tenantId", tenantId)
                .claim("roles", String.join(",", roles))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtProperties.getAccessTokenTtl() * 1000))
                .signWith(getKey())
                .compact();
    }

    public String generateRefreshToken(String userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                // jti 唯一标识：登出/轮转时按 token 维度拉黑（而非按 userId 一把拉黑所有会话）
                .claim("jti", java.util.UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + jwtProperties.getRefreshTokenTtl() * 1000))
                .signWith(getKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    public String getJti(String token) {
        return parseClaims(token).get("jti", String.class);
    }
}
