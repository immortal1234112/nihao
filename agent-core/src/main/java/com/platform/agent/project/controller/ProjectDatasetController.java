package com.platform.agent.project.controller;

import com.platform.agent.project.dto.DatasetBindRequest;
import com.platform.agent.project.entity.ProjectDataset;
import com.platform.agent.project.service.ProjectService;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目知识库挂载控制器 — /api/v1/projects/{id}/datasets
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/datasets")
@RequiredArgsConstructor
public class ProjectDatasetController {

    private final ProjectService projectService;

    @GetMapping
    public Result<List<ProjectDataset>> list(@PathVariable String projectId) {
        return Result.ok(projectService.listDatasets(projectId));
    }

    @AuditLog(action = "BIND_PROJECT_DATASET")
    @PostMapping
    public Result<Void> bind(@PathVariable String projectId, @Valid @RequestBody DatasetBindRequest request) {
        projectService.bindDataset(projectId, request.getDatasetId());
        return Result.ok();
    }

    @AuditLog(action = "UNBIND_PROJECT_DATASET")
    @DeleteMapping("/{datasetId}")
    public Result<Void> unbind(@PathVariable String projectId, @PathVariable String datasetId) {
        projectService.unbindDataset(projectId, datasetId);
        return Result.ok();
    }
}
