package com.platform.agent.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.asset.dto.AssetCreateRequest;
import com.platform.agent.asset.dto.AssetUpdateRequest;
import com.platform.agent.asset.entity.Asset;
import com.platform.agent.asset.entity.AssetTag;
import com.platform.agent.asset.entity.AssetTaskRelation;
import com.platform.agent.asset.mapper.AssetMapper;
import com.platform.agent.asset.mapper.AssetTagMapper;
import com.platform.agent.asset.mapper.AssetTaskRelationMapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.mapper.ProjectMemberMapper;
import com.platform.agent.project.service.ProjectAccessService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.platform.feign.StorageFeignClient;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产服务 —— 列表/详情/创建/更新/收藏/审核/软删除/派生链。
 * 可见性：租户级资产全租户可见；项目资产仅项目成员（或管理员）可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final List<String> VALID_DERIVATIONS = List.of(
            Asset.DERIVATION_UPLOAD, Asset.DERIVATION_IMPORT, Asset.DERIVATION_GENERATED,
            Asset.DERIVATION_VARIANT, Asset.DERIVATION_REGEN, Asset.DERIVATION_EDIT,
            Asset.DERIVATION_CONVERT, Asset.DERIVATION_EXPORT);
    private static final List<String> VALID_TYPES = List.of(
            Asset.TYPE_IMAGE, Asset.TYPE_VIDEO, Asset.TYPE_AUDIO, Asset.TYPE_TEXT, Asset.TYPE_FILE);
    private static final List<String> VALID_STATUSES = List.of(
            Asset.STATUS_DRAFT, Asset.STATUS_UNDER_REVIEW, Asset.STATUS_APPROVED, Asset.STATUS_REJECTED);

    private final AssetMapper assetMapper;
    private final AssetTagMapper assetTagMapper;
    private final AssetTaskRelationMapper assetTaskRelationMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectAccessService projectAccessService;
    private final StorageFeignClient storageFeignClient;

    // ==================== 列表 ====================

    public PageResult<Map<String, Object>> list(String userId, String tenantId,
                                                String projectId, String assetType, String status,
                                                String tag, Boolean starred, String keyword,
                                                String derivationType, int page, int size) {
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getTenantId, tenantId);

        // 非管理员：项目资产只可见自己参与的项目
        if (!projectAccessService.isAdmin()) {
            List<ProjectMember> members = projectMemberMapper.selectList(
                    new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId));
            List<String> myProjects = members.stream().map(ProjectMember::getProjectId).toList();
            wrapper.and(w -> w.isNull(Asset::getProjectId)
                    .or(myProjects.isEmpty(), w2 -> w2.isNull(Asset::getProjectId))
                    .or(!myProjects.isEmpty(), w2 -> w2.in(Asset::getProjectId, myProjects)));
        }
        if (projectId != null && !projectId.isBlank()) {
            projectAccessService.requireVisible(projectId);
            wrapper.eq(Asset::getProjectId, projectId);
        }
        if (assetType != null && !assetType.isBlank()) {
            wrapper.eq(Asset::getAssetType, assetType);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Asset::getStatus, status);
        }
        if (derivationType != null && !derivationType.isBlank()) {
            wrapper.eq(Asset::getDerivationType, derivationType);
        }
        if (starred != null) {
            wrapper.eq(Asset::getStarred, starred ? 1 : 0);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Asset::getTitle, keyword);
        }
        if (tag != null && !tag.isBlank()) {
            List<AssetTag> tagged = assetTagMapper.selectList(new LambdaQueryWrapper<AssetTag>()
                    .eq(AssetTag::getTenantId, tenantId)
                    .eq(AssetTag::getName, tag));
            List<String> assetIds = tagged.stream().map(AssetTag::getAssetId).toList();
            if (assetIds.isEmpty()) {
                return PageResult.of(List.of(), page, size, 0);
            }
            wrapper.in(Asset::getId, assetIds);
        }
        wrapper.orderByDesc(Asset::getCreatedAt);

        Page<Asset> mpPage = assetMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Asset a : mpPage.getRecords()) {
            records.add(toRow(a, false));
        }
        attachFileUrls(records);
        return PageResult.of(records, page, size, mpPage.getTotal());
    }

    /** 列表行附带文件下载 URL（前端网格预览用）；批量拉取一次，失败不影响列表。 */
    private void attachFileUrls(List<Map<String, Object>> rows) {
        List<String> fileIds = rows.stream()
                .map(r -> (String) r.get("fileId"))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (fileIds.isEmpty()) {
            return;
        }
        try {
            Result<List<com.platform.feign.dto.FileDTO>> res = storageFeignClient.listByIds(fileIds);
            if (res == null || res.getCode() != 0 || res.getData() == null) {
                return;
            }
            Map<String, com.platform.feign.dto.FileDTO> byId = res.getData().stream()
                    .collect(java.util.stream.Collectors.toMap(com.platform.feign.dto.FileDTO::id, f -> f));
            for (Map<String, Object> row : rows) {
                String fileId = (String) row.get("fileId");
                if (fileId != null) {
                    row.put("file", byId.get(fileId));
                }
            }
        } catch (Exception e) {
            log.warn("attachFileUrls failed: {}", e.getMessage());
        }
    }

    // ==================== 详情 / 派生链 ====================

    public Map<String, Object> detail(String assetId) {
        Asset asset = requireVisibleAsset(assetId);
        Map<String, Object> row = toRow(asset, true);
        row.put("tags", listTags(assetId));
        if (asset.getFileId() != null) {
            Result<com.platform.feign.dto.FileDTO> fileRes = storageFeignClient.getFile(asset.getFileId());
            if (fileRes != null && fileRes.getCode() == 0 && fileRes.getData() != null) {
                row.put("file", fileRes.getData());
            }
        }
        return row;
    }

    /** 任务的归档资产列表（产出关系），供创作页"保存到项目"弹窗使用。 */
    public List<Map<String, Object>> listByTask(String taskId) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        List<AssetTaskRelation> relations = assetTaskRelationMapper.selectList(
                new LambdaQueryWrapper<AssetTaskRelation>()
                        .eq(AssetTaskRelation::getTaskId, taskId)
                        .eq(AssetTaskRelation::getRelationType, AssetTaskRelation.REL_OUTPUT));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AssetTaskRelation rel : relations) {
            Asset asset = assetMapper.selectById(rel.getAssetId());
            if (asset != null && com.platform.web.context.TenantContext.get().equals(asset.getTenantId())) {
                rows.add(toRow(asset, true));
            }
        }
        return rows;
    }

    /** 完整溯源：parent 链向上 + children 向下 + 关联任务。 */
    public Map<String, Object> lineage(String assetId) {
        Asset asset = requireVisibleAsset(assetId);

        List<Map<String, Object>> parents = new ArrayList<>();
        String cursor = asset.getParentAssetId();
        int hops = 0;
        while (cursor != null && hops++ < 20) {
            // 忽略软删过滤：父资产被删除后仍要在派生链中展示（deleted=true 由前端渲染占位）
            Asset parent = assetMapper.selectByIdIgnoreDeleted(cursor);
            if (parent == null) {
                break;
            }
            parents.add(Map.of("id", parent.getId(), "title", parent.getTitle(),
                    "derivationType", parent.getDerivationType(), "deleted", parent.getDeleted() != null && parent.getDeleted() == 1));
            cursor = parent.getParentAssetId();
        }

        List<Asset> children = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getParentAssetId, assetId)
                .orderByAsc(Asset::getCreatedAt));

        List<AssetTaskRelation> relations = assetTaskRelationMapper.selectList(
                new LambdaQueryWrapper<AssetTaskRelation>().eq(AssetTaskRelation::getAssetId, assetId));
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (AssetTaskRelation rel : relations) {
            AgentTask task = agentTaskMapper.selectById(rel.getTaskId());
            if (task != null) {
                tasks.add(Map.of("taskId", task.getId(), "relationType", rel.getRelationType(),
                        "status", task.getStatus(), "workflowType", task.getWorkflowType(),
                        "createdAt", task.getCreatedAt()));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("asset", toRow(asset, true));
        result.put("parents", parents);
        result.put("children", children.stream().map(c -> toRow(c, false)).toList());
        result.put("tasks", tasks);
        return result;
    }

    // ==================== 创建 / 更新 ====================

    public Asset create(String userId, String tenantId, AssetCreateRequest request) {
        checkWritableTarget(request.getProjectId());
        validateCreateRequest(request);

        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setProjectId(blankToNull(request.getProjectId()));
        asset.setParentAssetId(blankToNull(request.getParentAssetId()));
        asset.setDerivationType(request.getDerivationType());
        asset.setAssetType(request.getAssetType());
        asset.setTitle(request.getTitle() != null ? request.getTitle() : "未命名资产");
        asset.setDescription(request.getDescription());
        asset.setFileId(blankToNull(request.getFileId()));
        asset.setContentText(request.getContentText());
        asset.setPrompt(request.getPrompt());
        asset.setModelProvider(request.getModelProvider());
        asset.setModelCode(request.getModelCode());
        asset.setParams(request.getParams() != null ? JsonUtils.toJson(request.getParams()) : null);
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setStarred(0);
        asset.setCreatedBy(userId);
        assetMapper.insert(asset);

        replaceTags(tenantId, asset.getId(), request.getTags());
        return asset;
    }

    public Asset update(String assetId, AssetUpdateRequest request) {
        Asset asset = requireWritableAsset(assetId);

        // 移动归属：源与目标项目均需写权限
        if (request.getProjectId() != null) {
            String targetProjectId = blankToNull(request.getProjectId());
            checkWritableTarget(targetProjectId);
            asset.setProjectId(targetProjectId);
        }
        if (request.getTitle() != null) {
            asset.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            asset.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            if (!VALID_STATUSES.contains(request.getStatus())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "非法状态: " + request.getStatus());
            }
            asset.setStatus(request.getStatus());
        }
        assetMapper.updateById(asset);

        if (request.getTags() != null) {
            replaceTags(asset.getTenantId(), assetId, request.getTags());
        }
        return asset;
    }

    // ==================== 收藏 / 审核 / 删除 ====================

    public void star(String assetId, boolean starred) {
        Asset asset = requireVisibleAsset(assetId);
        asset.setStarred(starred ? 1 : 0);
        assetMapper.updateById(asset);
    }

    public void submitReview(String assetId) {
        Asset asset = requireWritableAsset(assetId);
        if (!Asset.STATUS_DRAFT.equals(asset.getStatus()) && !Asset.STATUS_REJECTED.equals(asset.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅草稿/已驳回资产可提交审核");
        }
        asset.setStatus(Asset.STATUS_UNDER_REVIEW);
        assetMapper.updateById(asset);
    }

    /** 审核通过/驳回：项目资产需 OWNER/REVIEWER；租户级资产仅管理员。 */
    public void review(String assetId, boolean approved) {
        Asset asset = requireVisibleAsset(assetId);
        if (asset.getProjectId() != null) {
            projectAccessService.requireProjectRole(asset.getProjectId(),
                    ProjectMember.ROLE_OWNER, ProjectMember.ROLE_REVIEWER);
        } else if (!projectAccessService.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (!Asset.STATUS_UNDER_REVIEW.equals(asset.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "资产不在审核中状态");
        }
        asset.setStatus(approved ? Asset.STATUS_APPROVED : Asset.STATUS_REJECTED);
        assetMapper.updateById(asset);
    }

    /** 软删除（链不剪断，子资产 parent 引用保留）。 */
    public void delete(String assetId) {
        requireWritableAsset(assetId);
        assetMapper.deleteById(assetId);
    }

    // ==================== 权限校验 ====================

    /** 可见性：同租户 +（项目资产需成员或管理员）。 */
    public Asset requireVisibleAsset(String assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new BizException(ErrorCode.ASSET_NOT_FOUND);
        }
        String tenantId = com.platform.web.context.TenantContext.get();
        if (!tenantId.equals(asset.getTenantId())) {
            throw new BizException(ErrorCode.ASSET_NOT_FOUND);
        }
        if (asset.getProjectId() != null) {
            projectAccessService.requireVisible(asset.getProjectId());
        }
        return asset;
    }

    /** 写权限：项目资产 OWNER/EDITOR；租户级资产创建者或管理员。 */
    private Asset requireWritableAsset(String assetId) {
        Asset asset = requireVisibleAsset(assetId);
        if (asset.getProjectId() != null) {
            projectAccessService.requireProjectRole(asset.getProjectId(),
                    ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        } else if (!projectAccessService.isAdmin()
                && !UserContext.getUserId().equals(asset.getCreatedBy())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return asset;
    }

    /** 创建/移动目标：项目需写权限；租户级素材库无需额外校验。 */
    private void checkWritableTarget(String projectId) {
        if (projectId != null) {
            projectAccessService.requireProjectRole(projectId,
                    ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        }
    }

    // ==================== 内部工具 ====================

    private void validateCreateRequest(AssetCreateRequest request) {
        if (!VALID_DERIVATIONS.contains(request.getDerivationType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法派生类型: " + request.getDerivationType());
        }
        if (!VALID_TYPES.contains(request.getAssetType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法资产类型: " + request.getAssetType());
        }
        if (Asset.TYPE_TEXT.equals(request.getAssetType())
                && (request.getContentText() == null || request.getContentText().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "TEXT 资产必须提供 contentText");
        }
        if (!Asset.TYPE_TEXT.equals(request.getAssetType())
                && (request.getFileId() == null || request.getFileId().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非 TEXT 资产必须提供 fileId");
        }
    }

    private void replaceTags(String tenantId, String assetId, List<String> tags) {
        if (tags == null) {
            return;
        }
        assetTagMapper.delete(new LambdaQueryWrapper<AssetTag>().eq(AssetTag::getAssetId, assetId));
        for (String name : tags.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList()) {
            AssetTag tagRow = new AssetTag();
            tagRow.setTenantId(tenantId);
            tagRow.setAssetId(assetId);
            tagRow.setName(name.length() > 64 ? name.substring(0, 64) : name);
            assetTagMapper.insert(tagRow);
        }
    }

    private List<String> listTags(String assetId) {
        return assetTagMapper.selectList(new LambdaQueryWrapper<AssetTag>()
                        .eq(AssetTag::getAssetId, assetId))
                .stream().map(AssetTag::getName).toList();
    }

    private Map<String, Object> toRow(Asset a, boolean full) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", a.getId());
        row.put("projectId", a.getProjectId());
        row.put("parentAssetId", a.getParentAssetId());
        row.put("derivationType", a.getDerivationType());
        row.put("assetType", a.getAssetType());
        row.put("title", a.getTitle());
        row.put("fileId", a.getFileId());
        row.put("thumbFileId", a.getThumbFileId());
        row.put("status", a.getStatus());
        row.put("starred", a.getStarred());
        row.put("createdAt", a.getCreatedAt());
        row.put("createdBy", a.getCreatedBy());
        if (full) {
            row.put("description", a.getDescription());
            row.put("contentText", a.getContentText());
            row.put("prompt", a.getPrompt());
            row.put("modelProvider", a.getModelProvider());
            row.put("modelCode", a.getModelCode());
            row.put("params", a.getParams());
        }
        return row;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
