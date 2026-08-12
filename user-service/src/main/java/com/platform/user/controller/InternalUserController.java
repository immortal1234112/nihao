package com.platform.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.feign.dto.QuotaDTO;
import com.platform.feign.dto.UserDTO;
import com.platform.user.entity.UserAccount;
import com.platform.user.entity.UserTenant;
import com.platform.user.mapper.UserAccountMapper;
import com.platform.user.mapper.UserTenantMapper;
import com.platform.user.service.AuthService;
import com.platform.user.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 内部 Feign 调用控制器 — /api/v1/internal/users
 * 供其他微服务通过 FeignClient 调用，不走 Gateway 鉴权。
 */
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserAccountMapper userAccountMapper;
    private final UserTenantMapper userTenantMapper;
    private final QuotaService quotaService;
    private final AuthService authService;

    /**
     * 校验 API Key（供网关 ApiKeyAuthFilter 调用），返回 key 对应的身份。
     */
    @PostMapping("/api-key/validate")
    public Result<Map<String, String>> validateApiKey(@RequestBody Map<String, String> body) {
        try {
            return Result.ok(authService.validateApiKey(body.get("apiKey")));
        } catch (BizException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 获取用户信息（供 Feign 调用）。
     */
    @GetMapping("/{userId}")
    public Result<UserDTO> getUser(@PathVariable String userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            return Result.fail(ErrorCode.USER_NOT_FOUND);
        }

        // 获取第一个租户
        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, userId)
        );
        String tenantId = userTenants.isEmpty() ? "" : userTenants.get(0).getTenantId();

        UserDTO dto = new UserDTO(user.getId(), user.getUsername(), user.getEmail(), tenantId, user.getStatus());
        return Result.ok(dto);
    }

    /**
     * 获取用户配额（供 Feign 调用）。
     */
    @GetMapping("/{userId}/quota")
    public Result<QuotaDTO> getQuota(@PathVariable String userId, @RequestParam String type) {
        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, userId)
        );
        String tenantId = userTenants.isEmpty() ? "default" : userTenants.get(0).getTenantId();

        long limit = getDefaultLimit(type);
        long windowSeconds = 86400;
        Long used = quotaService.getRemaining(tenantId, userId, type, windowSeconds);
        long usedValue = used != null ? used : 0;

        QuotaDTO dto = new QuotaDTO(type, limit, usedValue);
        return Result.ok(dto);
    }

    /**
     * 消费配额（供 Feign 调用）。
     */
    @PostMapping("/{userId}/quota/consume")
    public Result<Boolean> consumeQuota(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        String type = (String) body.getOrDefault("type", "IMAGE");
        int amount = body.containsKey("amount") ? ((Number) body.get("amount")).intValue() : 1;

        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, userId)
        );
        String tenantId = userTenants.isEmpty() ? "default" : userTenants.get(0).getTenantId();

        long limit = getDefaultLimit(type);
        long windowSeconds = 86400;

        long remaining = quotaService.checkAndConsume(tenantId, userId, type, amount, limit, windowSeconds);
        boolean success = remaining >= 0;
        return Result.ok(success);
    }

    private long getDefaultLimit(String type) {
        return switch (type.toUpperCase()) {
            case "IMAGE" -> 100;
            case "VIDEO" -> 20;
            case "STORAGE" -> 1073741824;
            case "API_CALL" -> 1000;
            default -> 100;
        };
    }
}
