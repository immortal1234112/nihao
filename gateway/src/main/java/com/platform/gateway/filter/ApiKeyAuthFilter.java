package com.platform.gateway.filter;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * API Key 鉴权全局过滤器 — 仅作用于 /api/v1/open/**。
 * 校验 X-Api-Key：调 user-service 内部接口换取 userId/tenantId，
 * 成功则注入身份请求头放行，失败返回 401。顺序在 JwtAuthFilter(order 0) 之前。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private final WebClient.Builder webClientBuilder;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> API_KEY_PATHS = Arrays.asList("/api/v1/open/**");
    private static final String VALIDATE_URL = "lb://user-service/api/v1/internal/users/api-key/validate";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (API_KEY_PATHS.stream().noneMatch(p -> PATH_MATCHER.match(p, path))) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return respond(exchange, ErrorCode.API_KEY_INVALID);
        }

        return webClientBuilder.build().post()
                .uri(VALIDATE_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("apiKey", apiKey))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .map(this::parseResult)
                .flatMap(result -> {
                    if (result == null || result.getCode() != 0 || result.getData() == null) {
                        // 透传内部校验的错误码（如 API_KEY_EXPIRED），统一 401
                        ErrorCode code = resolveApiKeyError(result != null ? result.getCode() : null);
                        return respond(exchange, code);
                    }
                    Map<String, String> identity = result.getData();
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", identity.getOrDefault("userId", ""))
                            .header("X-Tenant-Id", identity.getOrDefault("tenantId", ""))
                            .header("X-Username", "")
                            .header("X-User-Roles", "")
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .onErrorResume(e -> {
                    log.error("API Key 校验调用失败: path={}, error={}", path, e.getMessage());
                    return respond(exchange, ErrorCode.API_KEY_INVALID);
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }

    // ========== 辅助方法 ==========

    private Result<Map<String, String>> parseResult(String body) {
        try {
            return JsonUtils.fromJson(body, Result.class);
        } catch (Exception e) {
            log.warn("API Key 校验响应解析失败: {}", body);
            return null;
        }
    }

    private ErrorCode resolveApiKeyError(Integer code) {
        return code != null && code == ErrorCode.API_KEY_EXPIRED.getCode()
                ? ErrorCode.API_KEY_EXPIRED
                : ErrorCode.API_KEY_INVALID;
    }

    private Mono<Void> respond(ServerWebExchange exchange, ErrorCode errorCode) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Result<Void> result = Result.fail(errorCode);
        result.setRequestId(exchange.getRequest().getId());
        byte[] bytes = JsonUtils.toJson(result).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
