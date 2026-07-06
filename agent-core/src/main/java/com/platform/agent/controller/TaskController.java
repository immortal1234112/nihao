package com.platform.agent.controller;

import com.platform.agent.dto.TaskSubmitRequest;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.service.TaskService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务控制器 — /api/v1/tasks
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @AuditLog(action = "SUBMIT_IMAGE_TASK")
    @PostMapping("/image")
    public Result<Map<String, Object>> submitImage(@Valid @RequestBody TaskSubmitRequest request) {
        request.setEngineType("IMAGE");
        if (request.getWorkflowType() == null) request.setWorkflowType("SINGLE");
        AgentTask task = taskService.submitTask(UserContext.getUserId(), getTenantId(), request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @AuditLog(action = "SUBMIT_VIDEO_TASK")
    @PostMapping("/video")
    public Result<Map<String, Object>> submitVideo(@Valid @RequestBody TaskSubmitRequest request) {
        request.setEngineType("VIDEO");
        if (request.getWorkflowType() == null) request.setWorkflowType("SINGLE");
        AgentTask task = taskService.submitTask(UserContext.getUserId(), getTenantId(), request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @AuditLog(action = "SUBMIT_BATCH_TASK")
    @PostMapping("/batch")
    public Result<Map<String, Object>> submitBatch(@Valid @RequestBody TaskSubmitRequest request) {
        request.setWorkflowType("BATCH");
        AgentTask task = taskService.submitTask(UserContext.getUserId(), getTenantId(), request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @AuditLog(action = "SUBMIT_WORKFLOW_TASK")
    @PostMapping("/workflow")
    public Result<Map<String, Object>> submitWorkflow(@Valid @RequestBody TaskSubmitRequest request) {
        request.setWorkflowType("PIPELINE");
        AgentTask task = taskService.submitTask(UserContext.getUserId(), getTenantId(), request);
        return Result.ok(taskService.getTaskDetail(task.getId()));
    }

    @GetMapping
    public Result<PageResult<AgentTask>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        PageResult<AgentTask> result = taskService.listTasks(UserContext.getUserId(), page, size, status);
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable String id) {
        return Result.ok(taskService.getTaskDetail(id));
    }

    @GetMapping("/{id}/result")
    public Result<Map<String, Object>> getResult(@PathVariable String id) {
        AgentTask task = taskService.getTask(id);
        return Result.ok(Map.of(
                "status", task.getStatus(),
                "result", task.getResult(),
                "errorMsg", task.getErrorMsg(),
                "steps", taskService.getTaskSteps(id)
        ));
    }

    @AuditLog(action = "CANCEL_TASK")
    @DeleteMapping("/{id}")
    public Result<Void> cancel(@PathVariable String id) {
        taskService.cancelTask(id, UserContext.getUserId());
        return Result.ok();
    }

    private String getTenantId() {
        return com.platform.web.context.TenantContext.get();
    }
}
