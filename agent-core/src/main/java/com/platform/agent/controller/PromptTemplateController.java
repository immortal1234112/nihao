package com.platform.agent.controller;

import com.platform.agent.entity.PromptTemplate;
import com.platform.agent.service.PromptTemplateService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 提示词模板控制器 — /api/v1/templates
 * 列表可见范围（PUBLIC + 本租户 + 本人），增删改仅限本人模板。
 */
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private static final int MAX_SIZE = 100;

    private final PromptTemplateService promptTemplateService;

    @GetMapping
    public Result<PageResult<PromptTemplate>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String engineType,
            @RequestParam(required = false) String ownerId) {
        if (page < 1 || size < 1 || size > MAX_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分页参数不合法（1 ≤ page，1 ≤ size ≤ 100）");
        }
        return Result.ok(promptTemplateService.list(
                UserContext.getUserId(), TenantContext.get(), page, size, category, engineType, ownerId));
    }

    @AuditLog(action = "CREATE_PROMPT_TEMPLATE")
    @PostMapping
    public Result<PromptTemplate> create(@RequestBody PromptTemplate template) {
        return Result.ok(promptTemplateService.create(
                UserContext.getUserId(), TenantContext.get(), template));
    }

    @AuditLog(action = "UPDATE_PROMPT_TEMPLATE")
    @PutMapping("/{id}")
    public Result<PromptTemplate> update(@PathVariable String id, @RequestBody PromptTemplate template) {
        return Result.ok(promptTemplateService.update(UserContext.getUserId(), id, template));
    }

    @AuditLog(action = "DELETE_PROMPT_TEMPLATE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        promptTemplateService.delete(UserContext.getUserId(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/use")
    public Result<Void> recordUsage(@PathVariable String id) {
        promptTemplateService.recordUsage(id);
        return Result.ok();
    }
}
