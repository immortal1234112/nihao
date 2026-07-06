package com.platform.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.service.TaskService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.annotation.RequireRole;
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

    private final AgentTaskMapper agentTaskMapper;
    private final TaskService taskService;

    @GetMapping("/stats")
    @RequireRole({"TENANT_ADMIN", "SUPER_ADMIN"})
    public Result<Map<String, Long>> getStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", agentTaskMapper.selectCount(null));
        stats.put("pending", countByStatus(AgentTask.STATUS_PENDING));
        stats.put("processing", countByStatus(AgentTask.STATUS_PROCESSING));
        stats.put("done", countByStatus(AgentTask.STATUS_DONE));
        stats.put("failed", countByStatus(AgentTask.STATUS_FAILED));
        stats.put("cancelled", countByStatus(AgentTask.STATUS_CANCELLED));
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
        task.setStatus("PASS".equals(action) ? AgentTask.STATUS_PASS : AgentTask.STATUS_REJECT);
        task.setCompletedAt(LocalDateTime.now());
        agentTaskMapper.updateById(task);
        return Result.ok();
    }

    private long countByStatus(String status) {
        return agentTaskMapper.selectCount(
                new LambdaQueryWrapper<AgentTask>().eq(AgentTask::getStatus, status)
        );
    }
}
