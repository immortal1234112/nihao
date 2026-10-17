package com.platform.agent.chat.controller;

import com.platform.agent.chat.entity.MemorySpace;
import com.platform.agent.chat.service.MemorySpaceService;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆空间管理 — /api/v1/llm/spaces
 * <p>
 * 空间是一套独立的「长期 + 短期」记忆。对话创建时选一个空间，该对话的记忆注入与提取都限定在内，
 * 从而实现「同时开多个对话、各自参考不同记忆」。空间在对话创建时绑定，之后不可变更。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm/spaces")
@RequiredArgsConstructor
public class MemorySpaceController {

    private final MemorySpaceService spaceService;

    /** 列表（默认空间排最前；首次访问会自动创建默认空间） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<MemorySpace> spaces = spaceService.list(UserContext.getUserId(), TenantContext.get());
        List<Map<String, Object>> result = new ArrayList<>(spaces.size());
        for (MemorySpace s : spaces) {
            result.add(toVO(s));
        }
        return Result.ok(result);
    }

    /** 新建空间 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        String name = request.get("name") == null ? null : String.valueOf(request.get("name"));
        String description = request.get("description") == null ? null : String.valueOf(request.get("description"));
        MemorySpace space = spaceService.create(UserContext.getUserId(), TenantContext.get(), name, description);
        return Result.ok(toVO(space));
    }

    /** 改名 / 改说明（默认空间不可改名） */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> request) {
        String name = request.get("name") == null ? null : String.valueOf(request.get("name"));
        String description = request.get("description") == null ? null : String.valueOf(request.get("description"));
        MemorySpace space = spaceService.update(UserContext.getUserId(), TenantContext.get(), id, name, description);
        return Result.ok(toVO(space));
    }

    /** 删除空间（软删，级联软删其下记忆；默认空间不可删） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        spaceService.delete(UserContext.getUserId(), TenantContext.get(), id);
        return Result.ok(null);
    }

    /** 取默认空间 ID（前端首次进入对话页时用来预选） */
    @GetMapping("/default")
    public Result<Map<String, Object>> defaultSpace() {
        String spaceId = spaceService.resolveDefaultSpaceId(UserContext.getUserId(), TenantContext.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spaceId", spaceId);
        return Result.ok(data);
    }

    private Map<String, Object> toVO(MemorySpace s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("isDefault", Integer.valueOf(1).equals(s.getIsDefault()));
        m.put("sortOrder", s.getSortOrder());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }
}
