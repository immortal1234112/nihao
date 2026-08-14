package com.platform.user.controller;

import com.platform.core.base.Result;
import com.platform.security.jwt.TokenPair;
import com.platform.user.dto.LoginRequest;
import com.platform.user.dto.RefreshTokenRequest;
import com.platform.user.dto.RegisterRequest;
import com.platform.user.entity.UserAccount;
import com.platform.user.service.AuthService;
import com.platform.web.annotation.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 — /api/v1/auth
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @AuditLog(action = "LOGIN")
    @PostMapping("/login")
    public Result<TokenPair> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = getClientIp(httpRequest);
        TokenPair tokenPair = authService.login(request, ip);
        return Result.ok(tokenPair);
    }

    @AuditLog(action = "REGISTER")
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(request);
        return Result.ok(user.getId());
    }

    @AuditLog(action = "REFRESH_TOKEN")
    @PostMapping("/refresh")
    public Result<TokenPair> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPair tokenPair = authService.refresh(request.getRefreshToken());
        return Result.ok(tokenPair);
    }

    @AuditLog(action = "LOGOUT")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody RefreshTokenRequest request) {
        String refreshToken = request != null ? request.getRefreshToken() : null;
        String accessToken = request != null ? request.getAccessToken() : null;
        authService.logout(refreshToken, accessToken);
        return Result.ok();
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }
        return request.getRemoteAddr();
    }
}
