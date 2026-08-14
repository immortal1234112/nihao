package com.platform.securitysvc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.core.base.PageResult;
import com.platform.securitysvc.entity.AuditLog;
import com.platform.securitysvc.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志查询服务 — 分页 + 多条件筛选。
 * 权限边界由 Controller 层控制（SUPER_ADMIN 全平台；TENANT_ADMIN 仅本租户）。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public PageResult<AuditLog> list(String tenantId, String userId, String action, String result,
                                     LocalDateTime startTime, LocalDateTime endTime,
                                     int page, int size) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        // 租户强制过滤（非空时必须在前：TENANT_ADMIN 只能看本租户）
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(AuditLog::getTenantId, tenantId);
        }
        if (userId != null && !userId.isBlank()) wrapper.eq(AuditLog::getUserId, userId);
        if (action != null && !action.isBlank()) wrapper.eq(AuditLog::getAction, action);
        if (result != null && !result.isBlank()) wrapper.eq(AuditLog::getResult, result);
        if (startTime != null) wrapper.ge(AuditLog::getActionTime, startTime);
        if (endTime != null) wrapper.le(AuditLog::getActionTime, endTime);
        wrapper.orderByDesc(AuditLog::getActionTime);

        Page<AuditLog> resultPage = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(resultPage.getRecords(), page, size, resultPage.getTotal());
    }
}
