package com.platform.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.user.entity.Tenant;
import com.platform.user.entity.UserTenant;
import com.platform.user.mapper.TenantMapper;
import com.platform.user.mapper.UserTenantMapper;
import com.platform.web.annotation.AuditLog;
import com.platform.web.annotation.RequireRole;
import com.platform.web.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 租户控制器 — /api/v1/tenants
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;

    @GetMapping("/{id}")
    public Result<Tenant> getTenant(@PathVariable String id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND, "租户不存在");
        }
        return Result.ok(tenant);
    }

    @AuditLog(action = "UPDATE_TENANT")
    @RequireRole({UserTenant.ROLE_TENANT_ADMIN, UserTenant.ROLE_SUPER_ADMIN})
    @PutMapping("/{id}")
    public Result<Void> updateTenant(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND, "租户不存在");
        }
        if (body.containsKey("name")) {
            tenant.setName((String) body.get("name"));
        }
        if (body.containsKey("quotaConfig")) {
            tenant.setQuotaConfig(body.get("quotaConfig").toString());
        }
        tenantMapper.updateById(tenant);
        return Result.ok();
    }

    @GetMapping("/{id}/members")
    public Result<List<Map<String, Object>>> getMembers(@PathVariable String id) {
        List<UserTenant> userTenants = userTenantMapper.selectList(
                new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getTenantId, id)
        );
        List<Map<String, Object>> members = userTenants.stream().map(ut -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", ut.getId());
            m.put("userId", ut.getUserId());
            m.put("tenantId", ut.getTenantId());
            m.put("roleCode", ut.getRoleCode());
            return m;
        }).toList();
        return Result.ok(members);
    }

    @AuditLog(action = "ADD_MEMBER")
    @RequireRole({UserTenant.ROLE_TENANT_ADMIN, UserTenant.ROLE_SUPER_ADMIN})
    @PostMapping("/{id}/members")
    public Result<Map<String, Object>> addMember(@PathVariable String id, @RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String roleCode = body.getOrDefault("roleCode", UserTenant.ROLE_VIEWER);

        // 检查是否已存在
        Long count = userTenantMapper.selectCount(
                new LambdaQueryWrapper<UserTenant>()
                        .eq(UserTenant::getTenantId, id)
                        .eq(UserTenant::getUserId, userId)
        );
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户已是该租户成员");
        }

        UserTenant ut = new UserTenant();
        ut.setUserId(userId);
        ut.setTenantId(id);
        ut.setRoleCode(roleCode);
        userTenantMapper.insert(ut);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", ut.getId());
        result.put("userId", userId);
        result.put("tenantId", id);
        result.put("roleCode", roleCode);
        return Result.ok(result);
    }

    @AuditLog(action = "REMOVE_MEMBER")
    @RequireRole({UserTenant.ROLE_TENANT_ADMIN, UserTenant.ROLE_SUPER_ADMIN})
    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
        userTenantMapper.delete(
                new LambdaQueryWrapper<UserTenant>()
                        .eq(UserTenant::getTenantId, id)
                        .eq(UserTenant::getUserId, userId)
        );
        return Result.ok();
    }
}
