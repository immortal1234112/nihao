package com.platform.agent.asset.controller;

import com.platform.agent.asset.dto.AssetCreateRequest;
import com.platform.agent.asset.dto.AssetUpdateRequest;
import com.platform.agent.asset.entity.Asset;
import com.platform.agent.asset.service.AssetService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 资产控制器 — /api/v1/assets
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean starred,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String derivationType) {
        return Result.ok(assetService.list(UserContext.getUserId(), TenantContext.get(),
                projectId, assetType, status, tag, starred, keyword, derivationType, page, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable String id) {
        return Result.ok(assetService.detail(id));
    }

    @GetMapping("/{id}/lineage")
    public Result<Map<String, Object>> lineage(@PathVariable String id) {
        return Result.ok(assetService.lineage(id));
    }

    @GetMapping("/by-task/{taskId}")
    public Result<java.util.List<Map<String, Object>>> listByTask(@PathVariable String taskId) {
        return Result.ok(assetService.listByTask(taskId));
    }

    @AuditLog(action = "CREATE_ASSET")
    @PostMapping
    public Result<Asset> create(@RequestBody AssetCreateRequest request) {
        return Result.ok(assetService.create(UserContext.getUserId(), TenantContext.get(), request));
    }

    @AuditLog(action = "UPDATE_ASSET")
    @PutMapping("/{id}")
    public Result<Asset> update(@PathVariable String id, @RequestBody AssetUpdateRequest request) {
        return Result.ok(assetService.update(id, request));
    }

    @AuditLog(action = "STAR_ASSET")
    @PostMapping("/{id}/star")
    public Result<Void> star(@PathVariable String id) {
        assetService.star(id, true);
        return Result.ok();
    }

    @AuditLog(action = "UNSTAR_ASSET")
    @PostMapping("/{id}/unstar")
    public Result<Void> unstar(@PathVariable String id) {
        assetService.star(id, false);
        return Result.ok();
    }

    @AuditLog(action = "SUBMIT_ASSET_REVIEW")
    @PostMapping("/{id}/submit-review")
    public Result<Void> submitReview(@PathVariable String id) {
        assetService.submitReview(id);
        return Result.ok();
    }

    @AuditLog(action = "REVIEW_ASSET")
    @PostMapping("/{id}/review")
    public Result<Void> review(@PathVariable String id, @RequestParam boolean approved) {
        assetService.review(id, approved);
        return Result.ok();
    }

    @AuditLog(action = "DELETE_ASSET")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        assetService.delete(id);
        return Result.ok();
    }
}
