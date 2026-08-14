package com.platform.securitysvc.controller;

import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.securitysvc.entity.AuditLog;
import com.platform.securitysvc.service.AuditLogService;
import com.platform.web.annotation.RequireRole;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 审计日志查询控制器 — /api/v1/audit/logs
 * 权限：SUPER_ADMIN 全平台；TENANT_ADMIN 仅本租户（强制租户过滤，防跨租户越权）。
 */
@RestController
@RequestMapping("/api/v1/audit/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int MAX_SIZE = 100;

    private final AuditLogService auditLogService;

    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    @GetMapping
    public Result<PageResult<AuditLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        if (page < 1 || size < 1 || size > MAX_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分页参数不合法（1 ≤ page，1 ≤ size ≤ 100）");
        }
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "开始时间不能晚于结束时间");
        }
        // TENANT_ADMIN 强制限定本租户；SUPER_ADMIN 不传 tenantId 查全平台
        boolean isSuperAdmin = UserContext.getRoles().contains("SUPER_ADMIN");
        String tenantId = isSuperAdmin ? null : TenantContext.get();
        // 防御：仅对 TENANT_ADMIN 校验租户上下文（如租户绑定缺失）时拒绝而非退化为全平台查询
        // （SUPER_ADMIN 的 tenantId 恒为 null，不能套用同一检查，否则管理员永远无法查询）
        if (!isSuperAdmin && (tenantId == null || tenantId.isBlank())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "租户上下文缺失，无法查询审计日志");
        }
        return Result.ok(auditLogService.list(tenantId, userId, action, result, startTime, endTime, page, size));
    }
}
