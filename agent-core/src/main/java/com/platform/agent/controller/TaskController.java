package com.platform.agent.controller;

import com.platform.agent.dto.TaskSubmitRequest;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.service.TaskService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

/**
 * 任务控制器 — /api/v1/tasks
 */
@Slf4j
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
        // 归属校验（防 IDOR）：只能查看自己的任务
        return Result.ok(taskService.getTaskDetail(id, UserContext.getUserId()));
    }

    @GetMapping("/{id}/result")
    public Result<Map<String, Object>> getResult(@PathVariable String id) {
        AgentTask task = taskService.getTask(id, UserContext.getUserId());
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("status", task.getStatus());
        data.put("result", task.getResult());
        data.put("errorMsg", task.getErrorMsg());
        data.put("steps", taskService.getTaskSteps(id));
        return Result.ok(data);
    }

    @AuditLog(action = "DELETE_TASK")
    @DeleteMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable String id) {
        taskService.deleteTask(id, UserContext.getUserId());
        return Result.ok();
    }

    @AuditLog(action = "CANCEL_TASK")
    @DeleteMapping("/{id}")
    public Result<Void> cancel(@PathVariable String id) {
        taskService.cancelTask(id, UserContext.getUserId());
        return Result.ok();
    }

    private static final java.util.Set<String> ALLOWED_DOWNLOAD_DOMAINS = java.util.Set.of(
            "aliyuncs.com", "openai.com", "stability.ai", "midjourney.com",
            "runwayml.com", "klingai.com", "pika.art", "lumalabs.ai",
            "bigmodel.cn", "ufileos.com",
            "localhost", "127.0.0.1"
    );

    @AuditLog(action = "DOWNLOAD_RESULT")
    @GetMapping("/download")
    public void downloadProxy(@RequestParam String url, HttpServletResponse response) {
        try {
            // 域名白名单校验（精确后缀匹配，防绕过）
            String host = new URL(url).getHost().toLowerCase();
            boolean allowed = false;
            for (String domain : ALLOWED_DOWNLOAD_DOMAINS) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                response.sendError(400, "Unsupported image source");
                return;
            }
            URL urlObj = new URL(url);
            URLConnection conn = urlObj.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.connect();

            String contentType = conn.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            response.setContentType(contentType);
            int contentLength = conn.getContentLength();
            if (contentLength > 0) response.setContentLengthLong(contentLength);

            String ext = contentType.contains("png") ? "png"
                    : contentType.contains("jpeg") || contentType.contains("jpg") ? "jpg"
                    : contentType.contains("webp") ? "webp"
                    : contentType.contains("mp4") ? "mp4" : "bin";
            response.setHeader("Content-Disposition", "attachment; filename=\"generated." + ext + "\"");

            try (InputStream in = conn.getInputStream();
                 OutputStream out = response.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            log.error("Download proxy failed: url={}", url, e);
            try {
                response.sendError(500, "Download failed: " + e.getMessage());
            } catch (java.io.IOException ignored) {}
        }
    }

    private String getTenantId() {
        return com.platform.web.context.TenantContext.get();
    }
}
