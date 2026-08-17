package com.platform.agent.project.controller;

import com.platform.agent.project.dto.BriefUpdateRequest;
import com.platform.agent.project.service.BriefService;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 创作简报控制器 — /api/v1/projects/{id}/brief
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/brief")
@RequiredArgsConstructor
public class BriefController {

    private final BriefService briefService;

    @GetMapping
    public Result<Map<String, Object>> get(@PathVariable String projectId) {
        return Result.ok(briefService.get(projectId));
    }

    @AuditLog(action = "UPDATE_PROJECT_BRIEF")
    @PutMapping
    public Result<Void> update(@PathVariable String projectId, @RequestBody BriefUpdateRequest request) {
        briefService.update(projectId, request);
        return Result.ok();
    }
}
