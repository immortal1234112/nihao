package com.platform.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 租户上下文过滤器。
 * 确保 X-Tenant-Id 转发头存在于下游请求中。
 */
@Slf4j
@Component
public class TenantContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 仅对 API 路径处理
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");

        if (tenantId == null || tenantId.isEmpty()) {
            // 如果没有租户信息（如白名单路径），设为默认值
            tenantId = "";
            log.debug("X-Tenant-Id not present for path: {}, setting empty", path);
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-Tenant-Id", tenantId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return 2;
    }
}