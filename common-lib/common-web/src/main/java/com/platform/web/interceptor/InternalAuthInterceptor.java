package com.platform.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口鉴权拦截器 — 校验 /api/v1/internal/** 请求的 X-Internal-Token。
 * <p>
 * 服务间 Feign 直连（绕过网关）靠该令牌做服务级防线：调用方（common-feign
 * FeignConfig / 网关 ApiKeyAuthFilter）携带令牌，持有内部接口的服务校验。
 * 未配置 internal.auth-token 时默认拒绝（fail-closed）——内部接口含 API Key 校验、
 * 配额消费、文件删除等高权限操作，配置缺失必须显性化，不能静默放行。
 * 本地开发由 start.ps1 自动生成 .internal-token 并注入 INTERNAL_AUTH_TOKEN 环境变量，
 * 无需手工配置。
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthInterceptor.class);

    @Value("${internal.auth-token:}")
    private String internalToken;

    private volatile boolean warned = false;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 令牌未配置 → 失败关闭（原实现为空令牌放行的本地默认模式，已移除）
        if (internalToken == null || internalToken.isBlank()) {
            if (!warned) {
                log.error("internal.auth-token 未配置: 已拒绝全部内部接口访问。请设置环境变量 INTERNAL_AUTH_TOKEN (start.ps1 会自动生成 .internal-token)。");
                warned = true;
            }
            return writeUnauthorized(response);
        }

        // 恒定时间比对，避免时序侧信道（String.equals 在开头即返回，可泄露前缀信息）
        byte[] expected = internalToken.getBytes(StandardCharsets.UTF_8);
        String received = request.getHeader("X-Internal-Token");
        byte[] actual = received != null ? received.getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(expected, actual)) {
            // 只记录请求元数据与失败原因，绝不记录令牌原文（原实现 expected/received 全量入日志）
            log.warn("Internal auth FAILED: uri={}, source={}, requestId={}, reason=token-mismatch",
                    request.getRequestURI(), request.getRemoteAddr(), request.getHeader("X-Request-Id"));
            return writeUnauthorized(response);
        }
        return true;
    }

    private boolean writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = "{\"code\":401,\"message\":\"内部接口鉴权失败\"}".getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        return false;
    }
}
