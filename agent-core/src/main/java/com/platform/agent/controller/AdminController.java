package com.platform.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.core.config.ModelConfigService;
import com.platform.agent.service.TaskService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.annotation.AuditLog;
import com.platform.web.annotation.RequireRole;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台控制器 — /api/v1/admin
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final AgentTaskMapper agentTaskMapper;
    private final TaskService taskService;
    private final ModelConfigService modelConfigService;

    @GetMapping("/stats")
    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    public Result<Map<String, Long>> getStats() {
        boolean global = isSuperAdmin();
        String tenantId = TenantContext.get();
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", countAll(global, tenantId));
        stats.put("pending", countByStatus(AgentTask.STATUS_PENDING, global, tenantId));
        stats.put("processing", countByStatus(AgentTask.STATUS_PROCESSING, global, tenantId));
        stats.put("done", countByStatus(AgentTask.STATUS_DONE, global, tenantId));
        stats.put("failed", countByStatus(AgentTask.STATUS_FAILED, global, tenantId));
        stats.put("cancelled", countByStatus(AgentTask.STATUS_CANCELLED, global, tenantId));
        return Result.ok(stats);
    }

    @GetMapping("/tasks")
    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    public Result<PageResult<AgentTask>> listAllTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String userId) {

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentTask> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);

        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<>();
        // 租户隔离：TENANT_ADMIN 只能看本租户任务，SUPER_ADMIN 全量
        if (!isSuperAdmin()) {
            String tenantId = TenantContext.get();
            if (tenantId != null && !tenantId.isBlank()) {
                wrapper.eq(AgentTask::getTenantId, tenantId);
            }
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AgentTask::getStatus, status);
        }
        if (userId != null && !userId.isEmpty()) {
            wrapper.eq(AgentTask::getUserId, userId);
        }
        wrapper.orderByDesc(AgentTask::getCreatedAt);

        var result = agentTaskMapper.selectPage(mpPage, wrapper);
        return Result.ok(PageResult.of(result.getRecords(), page, size, result.getTotal()));
    }

    @PutMapping("/tasks/{id}/review")
    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    public Result<Void> reviewTask(@PathVariable String id, @RequestBody Map<String, String> body) {
        String action = body.get("action");
        if (!"PASS".equals(action) && !"REJECT".equals(action)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "action 必须为 PASS 或 REJECT");
        }
        AgentTask task = agentTaskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        // 归属校验：TENANT_ADMIN 只能审核本租户任务
        if (!isSuperAdmin() && !task.getTenantId().equals(TenantContext.get())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        task.setStatus("PASS".equals(action) ? AgentTask.STATUS_PASS : AgentTask.STATUS_REJECT);
        task.setCompletedAt(LocalDateTime.now());
        agentTaskMapper.updateById(task);
        return Result.ok();
    }

    @GetMapping("/model-configs")
    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    public Result<List<Map<String, Object>>> listModelConfigs() {
        return Result.ok(modelConfigService.listAll());
    }

    // model-configs 是全局配置（API Key/BaseUrl/模型），只有 SUPER_ADMIN 可写
    @PutMapping("/model-configs/{provider}")
    @RequireRole({ROLE_SUPER_ADMIN})
    @AuditLog(action = "UPDATE_MODEL_CONFIG")
    public Result<Void> updateModelConfig(
            @PathVariable String provider,
            @RequestBody Map<String, Object> body) {
        modelConfigService.saveConfig(
            provider,
            (String) body.get("apiKey"),
            (String) body.get("apiSecret"),
            (String) body.get("baseUrl"),
            (String) body.get("model"),
            body.get("enabled") != null ? ((Number) body.get("enabled")).intValue() : null
        );
        return Result.ok();
    }

    @PostMapping("/model-configs/refresh")
    @RequireRole({ROLE_SUPER_ADMIN})
    @AuditLog(action = "REFRESH_MODEL_CONFIG")
    public Result<Void> refreshModelConfigs() {
        modelConfigService.refreshCache();
        return Result.ok();
    }

    private boolean isSuperAdmin() {
        return UserContext.getRoles().contains(ROLE_SUPER_ADMIN);
    }

    private long countAll(boolean global, String tenantId) {
        if (global) return agentTaskMapper.selectCount(null);
        LambdaQueryWrapper<AgentTask> w = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) w.eq(AgentTask::getTenantId, tenantId);
        return agentTaskMapper.selectCount(w);
    }

    private long countByStatus(String status, boolean global, String tenantId) {
        LambdaQueryWrapper<AgentTask> w = new LambdaQueryWrapper<>();
        if (!global && tenantId != null && !tenantId.isBlank()) w.eq(AgentTask::getTenantId, tenantId);
        w.eq(AgentTask::getStatus, status);
        return agentTaskMapper.selectCount(w);
    }
}
