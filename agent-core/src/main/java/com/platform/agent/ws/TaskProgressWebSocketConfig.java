package com.platform.agent.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;

/**
 * WebSocket 注册 — /ws/task-progress。
 * 路径带 /ws/ 前缀：避免命中 common-web 的 REST 拦截器（WS 长连接无请求级上下文语义）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class TaskProgressWebSocketConfig implements WebSocketConfigurer {

    private final TaskProgressWsHandler taskProgressWsHandler;

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 逐项 trim：", " 分隔的多来源中，带空格项不会被吞掉
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addHandler(taskProgressWsHandler, "/ws/task-progress")
                .setAllowedOriginPatterns(origins);
    }

    /**
     * 会话 idle timeout：90s 无任何读写自动关闭 —— 未发 auth 帧的连接（未进心跳列表）由此兜底清理，
     * 已鉴权会话每 25s 收到服务端心跳（写入即刷新 idle），不受影响。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(90_000L);
        container.setMaxTextMessageBufferSize(64 * 1024);
        return container;
    }
}
