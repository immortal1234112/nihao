package com.platform.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.user.dto.CreateApiKeyRequest;
import com.platform.user.dto.UpdateUserRequest;
import com.platform.user.entity.ApiKey;
import com.platform.user.entity.UserAccount;
import com.platform.user.mapper.UserAccountMapper;
import com.platform.user.service.AuthService;
import com.platform.user.service.QuotaService;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户控制器 — /api/v1/users
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserAccountMapper userAccountMapper;
    private final AuthService authService;
    private final QuotaService quotaService;

    // ==================== 个人信息 ====================

    @GetMapping("/me")
    public Result<Map<String, Object>> getMe() {
        UserAccount user = userAccountMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("email", user.getEmail());
        result.put("phone", user.getPhone());
        result.put("avatarUrl", user.getAvatarUrl());
        result.put("status", user.getStatus());
        result.put("createdAt", user.getCreatedAt());
        return Result.ok(result);
    }

    @AuditLog(action = "UPDATE_PROFILE")
    @PutMapping("/me")
    public Result<Void> updateMe(@Valid @RequestBody UpdateUserRequest request) {
        UserAccount user = userAccountMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        userAccountMapper.updateById(user);
        return Result.ok();
    }

    // ==================== 配额 ====================

    @GetMapping("/me/quota")
    public Result<Map<String, Long>> getMyQuota(@RequestParam(defaultValue = "IMAGE") String type) {
        String userId = UserContext.getUserId();
        String tenantId = TenantContext.get();

        // 从配置读取限额（默认值）
        long limit = getDefaultLimit(type);
        long windowSeconds = 86400; // 默认每天

        Long used = quotaService.getRemaining(tenantId, userId, type, windowSeconds);
        long usedValue = used != null ? used : 0;

        Map<String, Long> result = new HashMap<>();
        result.put("limit", limit);
        result.put("used", usedValue);
        result.put("remaining", Math.max(0, limit - usedValue));
        return Result.ok(result);
    }

    // ==================== API Key ====================

    @GetMapping("/me/api-keys")
    public Result<List<ApiKey>> listApiKeys() {
        List<ApiKey> keys = authService.listApiKeys(UserContext.getUserId(), TenantContext.get());
        return Result.ok(keys);
    }

    @AuditLog(action = "CREATE_API_KEY")
    @PostMapping("/me/api-keys")
    public Result<Map<String, String>> createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        String rawKey = authService.createApiKey(
                UserContext.getUserId(),
                TenantContext.get(),
                request.getName(),
                request.getRateLimitQpm(),
                request.getExpiresAt()
        );
        Map<String, String> result = new HashMap<>();
        result.put("apiKey", rawKey);
        result.put("hint", "请妥善保管，此 Key 仅显示一次");
        return Result.ok(result);
    }

    @AuditLog(action = "REVOKE_API_KEY")
    @DeleteMapping("/me/api-keys/{keyId}")
    public Result<Void> revokeApiKey(@PathVariable String keyId) {
        authService.revokeApiKey(keyId, UserContext.getUserId());
        return Result.ok();
    }

    // ========== 辅助方法 ==========

    private long getDefaultLimit(String type) {
        return switch (type.toUpperCase()) {
            case "IMAGE" -> 100;
            case "VIDEO" -> 20;
            case "STORAGE" -> 1073741824; // 1GB 字节
            case "API_CALL" -> 1000;
            default -> 100;
        };
    }
}
