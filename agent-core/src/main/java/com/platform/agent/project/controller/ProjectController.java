package com.platform.agent.project.controller;

import com.platform.agent.project.dto.ProjectCreateRequest;
import com.platform.agent.project.dto.ProjectUpdateRequest;
import com.platform.agent.project.entity.Project;
import com.platform.agent.project.service.ProjectService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 项目控制器 — /api/v1/projects
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @AuditLog(action = "CREATE_PROJECT")
    @PostMapping
    public Result<Project> create(@Valid @RequestBody ProjectCreateRequest request) {
        return Result.ok(projectService.create(UserContext.getUserId(), TenantContext.get(), request));
    }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String scope) {
        return Result.ok(projectService.list(UserContext.getUserId(), TenantContext.get(), scope, page, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable String id) {
        return Result.ok(projectService.detail(id));
    }

    @AuditLog(action = "UPDATE_PROJECT")
    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable String id, @RequestBody ProjectUpdateRequest request) {
        return Result.ok(projectService.update(id, request));
    }

    @AuditLog(action = "ARCHIVE_PROJECT")
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable String id) {
        projectService.setArchived(id, true);
        return Result.ok();
    }

    @AuditLog(action = "UNARCHIVE_PROJECT")
    @PostMapping("/{id}/unarchive")
    public Result<Void> unarchive(@PathVariable String id) {
        projectService.setArchived(id, false);
        return Result.ok();
    }

    @GetMapping("/{id}/timeline")
    public Result<Map<String, Object>> timeline(@PathVariable String id) {
        return Result.ok(projectService.timeline(id));
    }
}
