package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.PromptTemplate;
import com.platform.agent.mapper.PromptTemplateMapper;
import com.platform.core.base.PageResult;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 提示词模板服务 —— 列表（可见范围过滤）/ 创建 / 编辑 / 删除 / 使用计数。
 * 可见规则：PUBLIC 全平台 + TENANT 本租户 + PRIVATE 本人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateMapper promptTemplateMapper;

    public PageResult<PromptTemplate> list(String userId, String tenantId, int page, int size,
                                           String category, String engineType, String ownerId) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(PromptTemplate::getScope, PromptTemplate.SCOPE_PUBLIC)
                .or(q -> q.eq(PromptTemplate::getScope, PromptTemplate.SCOPE_TENANT)
                        .eq(PromptTemplate::getTenantId, tenantId))
                .or(q -> q.eq(PromptTemplate::getScope, PromptTemplate.SCOPE_PRIVATE)
                        .eq(PromptTemplate::getUserId, userId)));
        // 管理页「我的模板」：限定创建者，避免前端过滤破坏分页
        if (ownerId != null && !ownerId.isBlank()) {
            wrapper.eq(PromptTemplate::getUserId, ownerId);
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(PromptTemplate::getCategory, category);
        }
        if (engineType != null && !engineType.isBlank() && !"ALL".equalsIgnoreCase(engineType)) {
            wrapper.and(w -> w.eq(PromptTemplate::getEngineType, engineType)
                    .or(q -> q.eq(PromptTemplate::getEngineType, "ALL")));
        }
        // 固定排序：官方精选优先，其次租户/私有，同 scope 内按使用次数与创建时间
        wrapper.last("ORDER BY FIELD(scope, 'PUBLIC', 'TENANT', 'PRIVATE'), usage_count DESC, created_at DESC");

        Page<PromptTemplate> result = promptTemplateMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    private static final java.util.Set<String> SCOPES =
            java.util.Set.of(PromptTemplate.SCOPE_PUBLIC, PromptTemplate.SCOPE_TENANT, PromptTemplate.SCOPE_PRIVATE);
    private static final java.util.Set<String> ENGINE_TYPES =
            java.util.Set.of("IMAGE", "VIDEO", "ALL");

    public PromptTemplate create(String userId, String tenantId, PromptTemplate template) {
        if (template.getName() == null || template.getName().isBlank()
                || template.getPrompt() == null || template.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模板名称和提示词不能为空");
        }
        validateEnums(template.getScope(), template.getEngineType());
        // 公开模板=官方精选，仅由种子数据产生；普通用户只能建租户/私有模板（防 API 绕过前端提权）
        if (PromptTemplate.SCOPE_PUBLIC.equals(template.getScope())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "公开模板由平台维护，不可自行创建");
        }
        if (template.getScope() == null || template.getScope().isBlank()) {
            template.setScope(PromptTemplate.SCOPE_PRIVATE);
        }
        template.setUserId(userId);
        template.setTenantId(tenantId);
        template.setUsageCount(0);
        if (template.getEngineType() == null || template.getEngineType().isBlank()) {
            template.setEngineType("ALL");
        }
        promptTemplateMapper.insert(template);
        return template;
    }

    public PromptTemplate update(String userId, String id, PromptTemplate template) {
        PromptTemplate existing = requireOwner(userId, id);
        validateEnums(template.getScope(), template.getEngineType());
        // 公开模板=官方精选，仅由种子数据产生；不允许把模板改成 PUBLIC 提权（防 API 绕过前端）
        if (PromptTemplate.SCOPE_PUBLIC.equals(template.getScope())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "公开模板由平台维护，不可自行设置");
        }
        if (template.getName() != null && template.getName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模板名称不能为空");
        }
        if (template.getPrompt() != null && template.getPrompt().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "提示词不能为空");
        }
        if (template.getName() != null) existing.setName(template.getName());
        if (template.getCategory() != null) existing.setCategory(template.getCategory());
        if (template.getEngineType() != null) existing.setEngineType(template.getEngineType());
        if (template.getPrompt() != null) existing.setPrompt(template.getPrompt());
        if (template.getNegativePrompt() != null) existing.setNegativePrompt(template.getNegativePrompt());
        if (template.getScope() != null) existing.setScope(template.getScope());
        promptTemplateMapper.updateById(existing);
        return existing;
    }

    /** scope/engineType 白名单校验，非法枚举拒绝入库（脏数据在列表查询中不可见且无法清理） */
    private void validateEnums(String scope, String engineType) {
        if (scope != null && !scope.isBlank() && !SCOPES.contains(scope)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "可见范围不合法（PUBLIC/TENANT/PRIVATE）");
        }
        if (engineType != null && !engineType.isBlank() && !ENGINE_TYPES.contains(engineType)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "适用引擎不合法（IMAGE/VIDEO/ALL）");
        }
    }

    public void delete(String userId, String id) {
        PromptTemplate existing = requireOwner(userId, id);
        promptTemplateMapper.deleteById(existing.getId());
    }

    /** 创作页点击「使用」后累计次数（失败静默不影响主流程；DB 侧原子递增避免并发丢失） */
    public void recordUsage(String id) {
        try {
            promptTemplateMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PromptTemplate>()
                    .eq(PromptTemplate::getId, id)
                    .setSql("usage_count = IFNULL(usage_count, 0) + 1"));
        } catch (Exception e) {
            log.warn("record template usage failed: id={}", id, e);
        }
    }

    /** 归属校验：仅创建者本人可编辑/删除自己的模板（含 PRIVATE/TENANT）；PUBLIC 种子模板禁止删改 */
    private PromptTemplate requireOwner(String userId, String id) {
        PromptTemplate existing = promptTemplateMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND, "模板不存在");
        }
        if (PromptTemplate.SCOPE_PUBLIC.equals(existing.getScope())
                && !userId.equals(existing.getUserId())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "官方模板不可修改");
        }
        if (!userId.equals(existing.getUserId())) {
            throw new BizException(ErrorCode.NO_PERMISSION, "只能管理自己的模板");
        }
        return existing;
    }
}
