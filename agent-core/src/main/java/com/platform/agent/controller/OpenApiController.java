package com.platform.agent.controller;

import com.platform.agent.dto.TaskSubmitRequest;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.service.TaskService;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * OpenAPI 控制器 — /api/v1/open
 * 使用 X-Api-Key 头鉴权（由 Gateway 处理后注入 X-User-Id 等头）。
 */
@RestController
@RequestMapping("/api/v1/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final TaskService taskService;

    @PostMapping("/image")
    public Result<?> submitImage(
            @Valid @RequestBody TaskSubmitRequest request,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "") String tenantId) {
        request.setEngineType("IMAGE");
        if (request.getWorkflowType() == null) request.setWorkflowType("SINGLE");
        AgentTask task = taskService.submitTask(userId, tenantId, request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @PostMapping("/video")
    public Result<?> submitVideo(
            @Valid @RequestBody TaskSubmitRequest request,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "") String tenantId) {
        request.setEngineType("VIDEO");
        if (request.getWorkflowType() == null) request.setWorkflowType("SINGLE");
        AgentTask task = taskService.submitTask(userId, tenantId, request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @GetMapping("/tasks/{id}")
    public Result<?> getTask(@PathVariable String id) {
        return Result.ok(taskService.getTaskDetail(id));
    }

    @GetMapping("/tasks/{id}/result")
    public Result<?> getTaskResult(@PathVariable String id) {
        AgentTask task = taskService.getTask(id);
        return Result.ok(java.util.Map.of(
                "status", task.getStatus(),
                "result", task.getResult()
        ));
    }
}
