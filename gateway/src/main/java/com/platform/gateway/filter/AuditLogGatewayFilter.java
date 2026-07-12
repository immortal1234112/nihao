package com.platform.gateway.filter;

import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.AuditLogMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

/**
 * 审计日志网关过滤器。
 * 记录每个 API 请求的路径、方法、状态码、耗时，
 * 异步发送 AuditLogMessage 到 RabbitMQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogGatewayFilter implements GlobalFilter, Ordered {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 仅对 API 路径记录审计日志
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        long start = System.currentTimeMillis();

        String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name()
                : "UNKNOWN";
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        String clientIp = getClientIp(exchange);

        return chain.filter(exchange)
                .then(Mono.<Void>fromRunnable(() -> {
                    long durationMs = System.currentTimeMillis() - start;
                    HttpStatus status = (HttpStatus) exchange.getResponse().getStatusCode();
                    String result = (status != null && status.is2xxSuccessful()) ? "SUCCESS" : "FAIL";

                    AuditLogMessage message = new AuditLogMessage(
                            userId,
                            tenantId,
                            method + " " + path,
                            "duration=" + durationMs + "ms, status=" + (status != null ? status.value() : "UNKNOWN"),
                            clientIp,
                            LocalDateTime.now(),
                            result
                    );

                    try {
                        rabbitTemplate.convertAndSend(
                                ExchangeConstants.AUDIT_EXCHANGE,
                                "audit.log.gateway",
                                message
                        );
                    } catch (Exception e) {
                        log.warn("Failed to send audit log to MQ: {}", e.getMessage());
                    }
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> {
                    log.debug("Audit log filter error: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public int getOrder() {
        return 3;
    }

    // ========== 辅助方法 ==========

    private String getClientIp(ServerWebExchange exchange) {
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
}