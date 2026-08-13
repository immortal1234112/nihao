package com.platform.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 内部接口鉴权拦截器 — 校验 /api/v1/internal/** 请求的 X-Internal-Token。
 * <p>
 * 服务间 Feign 直连（绕过网关）靠该令牌做服务级防线：调用方（common-feign
 * FeignConfig / 网关 ApiKeyAuthFilter）携带令牌，持有内部接口的服务校验。
 * 未配置 internal.auth-token 时放行并告警一次（本地默认模式）；
 * 生产环境必须通过环境变量 INTERNAL_AUTH_TOKEN 配置统一令牌。
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthInterceptor.class);

    @Value("${internal.auth-token:}")
    private String internalToken;

    private volatile boolean warned = false;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (internalToken == null || internalToken.isBlank()) {
            if (!warned) {
                log.warn("internal.auth-token 未配置: 内部接口 /api/v1/internal/** 未启用令牌校验(本地模式)。生产环境请设置环境变量 INTERNAL_AUTH_TOKEN。");
                warned = true;
            }
            return true;
        }

        if (!internalToken.equals(request.getHeader("X-Internal-Token"))) {
            log.warn("[DIAG] Internal auth FAILED: expected={}, received={}",
                    internalToken, request.getHeader("X-Internal-Token"));
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            byte[] body = "{\"code\":401,\"message\":\"内部接口鉴权失败\"}".getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return false;
        }
        return true;
    }
}
