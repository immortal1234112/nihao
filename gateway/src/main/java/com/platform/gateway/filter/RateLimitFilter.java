package com.platform.gateway.filter;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 Redis 固定窗口的限流过滤器。
 * 每个请求按用户（或 IP）加路径维度计数，超限返回 429。
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    /** 窗口内允许的最大请求数 */
    @Value("${gateway.rate-limit.capacity:100}")
    private int capacity;

    /** 窗口时长（秒） */
    @Value("${gateway.rate-limit.window-seconds:60}")
    private int windowSeconds;

    /** 限流路径模式（默认对所有 /api/** 限流） */
    @Value("${gateway.rate-limit.path-patterns:/api/**}")
    private String pathPatterns;

    /** Lua 脚本：固定窗口计数 */
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = RedisScript.of("""
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])

            local current = redis.call('GET', key)
            if current and tonumber(current) >= limit then
                return 0
            end

            local count = redis.call('INCR', key)
            if count == 1 then
                redis.call('EXPIRE', key, window)
            end

            return 1
            """, Long.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 仅对 API 路径限流
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        // 构建限流 key: 优先使用用户 ID，否则使用客户端 IP
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isEmpty()) {
            userId = getClientIp(exchange);
        }
        String rateLimitKey = "ratelimit:" + userId + ":" + path;

        List<String> keys = Arrays.asList(rateLimitKey);
        List<String> args = Arrays.asList(String.valueOf(capacity), String.valueOf(windowSeconds));

        return reactiveRedisTemplate.execute(RATE_LIMIT_SCRIPT, keys, args)
                .next()
                .defaultIfEmpty(1L)
                .onErrorReturn(1L)
                .flatMap(allowed -> {
                    if (allowed != null && allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    return respondRateLimited(exchange);
                });
    }

    @Override
    public int getOrder() {
        return 1;
    }

    // ========== 辅助方法 ==========

    private String getClientIp(ServerWebExchange exchange) {
        // 尝试从常见代理头获取真实 IP
        String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            return ip.split(",")[0].trim();
        }
        ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getHostString()
                : "unknown";
    }

    private Mono<Void> respondRateLimited(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Result<Void> result = Result.fail(ErrorCode.RATE_LIMITED);
        result.setRequestId(exchange.getRequest().getId());
        byte[] bytes = JsonUtils.toJson(result).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}