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
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

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

    /** WebSocket 升级路径：浏览器无法自定义请求头，token 从 query 参数获取 */
    private static final List<String> WS_PATHS = List.of("/api/v1/tasks/ws/**");

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

        // 3. 提取 token：优先 Authorization 头；WS 升级请求（无自定义头）从 query 参数取
        String token = null;
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (matchesAny(WS_PATHS, path)) {
            String query = exchange.getRequest().getURI().getRawQuery();
            if (query != null) {
                token = extractTokenFromQuery(query);
            }
        }
        if (token == null) {
            return respondUnauthorized(exchange, ErrorCode.TOKEN_INVALID);
        }

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

        // 5. 登出撤销校验：access token 的 jti 在黑名单 → 立即失效
        //    （旧 token 无 jti 时跳过检查，兼容存量）
        String jti = claims.get("jti", String.class);
        if (jti != null && !jti.isBlank()) {
            return reactiveRedisTemplate.hasKey(JwtUtils.ACCESS_BLACKLIST_PREFIX + jti)
                    .flatMap(blacklisted -> {
                        if (Boolean.TRUE.equals(blacklisted)) {
                            return respondUnauthorized(exchange, ErrorCode.TOKEN_INVALID);
                        }
                        return forwardWithIdentity(exchange, chain, claims);
                    });
        }

        // 6. 注入请求头
        return forwardWithIdentity(exchange, chain, claims);
    }

    /** 注入身份请求头后放行（先剔除 query 中的 token 凭据，防止随转发请求泄漏到下游） */
    private Mono<Void> forwardWithIdentity(ServerWebExchange exchange, GatewayFilterChain chain, Claims claims) {
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String tenantId = claims.get("tenantId", String.class);
        String roles = claims.get("roles", String.class);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .uri(stripTokenFromUri(exchange.getRequest().getURI()))
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

    /** 从 query 中提取 token 参数；URL 解码失败（畸形 % 转义）返回 null → 按非法 token 401，不抛 500 */
    private String extractTokenFromQuery(String query) {
        for (String kv : query.split("&")) {
            String[] pair = kv.split("=", 2);
            if (pair.length == 2 && "token".equals(pair[0])) {
                try {
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    log.warn("Malformed token query param");
                    return null;
                }
            }
        }
        return null;
    }

    /** 剔除 query 中的 token 参数（仅 WS 鉴权用），其余参数原样保留；无剩余参数时 query 置空 */
    private URI stripTokenFromUri(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return uri;
        List<String> kept = new ArrayList<>();
        for (String kv : rawQuery.split("&")) {
            String[] pair = kv.split("=", 2);
            if (!(pair.length == 2 && "token".equals(pair[0]))) kept.add(kv);
        }
        String newQuery = kept.isEmpty() ? null : String.join("&", kept);
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), newQuery, uri.getFragment());
        } catch (URISyntaxException e) {
            // 原 URI 合法、只删除了子串，理论上不会失败；兜底返回原 URI（保留 token 优于阻断请求）
            log.warn("Rebuild URI failed after token strip: path={}", uri.getPath());
            return uri;
        }
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