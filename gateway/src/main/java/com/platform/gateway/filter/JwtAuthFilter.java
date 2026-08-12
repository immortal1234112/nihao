package com.platform.gateway.filter;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.platform.security.jwt.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 鉴权全局过滤器。
 * 白名单路径跳过鉴权，其余路径从 Authorization: Bearer 提取 JWT，
 * 验证后将用户信息注入转发请求头。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtUtils jwtUtils;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 白名单路径 — 跳过 JWT 鉴权（/open/** 由 ApiKeyAuthFilter 接管，meme 已改为登录态） */
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/open/**"
    );

    /** 放行的公共资源路径 */
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/actuator/**",
            "/favicon.ico"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 放行公共资源
        if (matchesAny(PUBLIC_PATHS, path)) {
            return chain.filter(exchange);
        }

        // 2. 白名单跳过 JWT
        if (matchesAny(WHITE_LIST, path)) {
            return chain.filter(exchange);
        }

        // 3. 提取 Authorization 头
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return respondUnauthorized(exchange, ErrorCode.TOKEN_INVALID);
        }

        String token = authHeader.substring(7);

        // 4. 验证 JWT（必须为 type=access 的访问令牌，refresh token 不可直接使用）
        Claims claims;
        try {
            claims = jwtUtils.parseClaims(token);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("expired")) {
                return respondUnauthorized(exchange, ErrorCode.TOKEN_EXPIRED);
            }
            return respondUnauthorized(exchange, ErrorCode.TOKEN_INVALID);
        }
        if (!"access".equals(claims.get("type", String.class))) {
            return respondUnauthorized(exchange, ErrorCode.TOKEN_INVALID);
        }

        // 5. 注入请求头
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String tenantId = claims.get("tenantId", String.class);
        String roles = claims.get("roles", String.class);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-Username", username != null ? username : "")
                .header("X-Tenant-Id", tenantId != null ? tenantId : "")
                .header("X-User-Roles", roles != null ? roles : "")
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    // ========== 辅助方法 ==========

    private boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private Mono<Void> respondUnauthorized(ServerWebExchange exchange, ErrorCode errorCode) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Result<Void> result = Result.fail(errorCode);
        result.setRequestId(exchange.getRequest().getId());
        byte[] bytes = JsonUtils.toJson(result).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}