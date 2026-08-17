package com.platform.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.asset.mapper.AssetMapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.project.dto.ProjectCreateRequest;
import com.platform.agent.project.dto.ProjectUpdateRequest;
import com.platform.agent.project.entity.CreativeBrief;
import com.platform.agent.project.entity.Project;
import com.platform.agent.project.entity.ProjectDataset;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.mapper.CreativeBriefMapper;
import com.platform.agent.project.mapper.ProjectDatasetMapper;
import com.platform.agent.project.mapper.ProjectMapper;
import com.platform.agent.project.mapper.ProjectMemberMapper;
import com.platform.core.base.PageResult;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目服务 —— 项目 CRUD、时间线、知识库挂载。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    /** 允许创建项目的租户角色（VIEWER 除外）。 */
    private static final List<String> CREATE_ALLOWED_ROLES = List.of(
            ProjectAccessService.ROLE_SUPER_ADMIN,
            ProjectAccessService.ROLE_TENANT_ADMIN,
            "DEVELOPER",
            "CONTENT_CREATOR"
    );

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final CreativeBriefMapper creativeBriefMapper;
    private final ProjectDatasetMapper projectDatasetMapper;
    private final AssetMapper assetMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final ProjectAccessService projectAccessService;

    /** 创建项目：创建者自动成为 OWNER（事务内保证 project 与成员行一致）。 */
    @Transactional
    public Project create(String userId, String tenantId, ProjectCreateRequest request) {
        boolean canCreate = UserContext.getRoles().stream().anyMatch(CREATE_ALLOWED_ROLES::contains);
        if (!canCreate) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        Project project = new Project();
        project.setTenantId(tenantId);
        project.setName(request.getName());
        project.setStatus(Project.STATUS_ACTIVE);
        project.setDeadline(request.getDeadline());
        projectMapper.insert(project);

        ProjectMember owner = new ProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(userId);
        owner.setRoleCode(ProjectMember.ROLE_OWNER);
        projectMemberMapper.insert(owner);
        return project;
    }

    /** 项目列表：默认"我参与的"，scope=all 且管理员可见全租户。 */
    public PageResult<Map<String, Object>> list(String userId, String tenantId, String scope, int page, int size) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .eq(Project::getTenantId, tenantId);
        if (!("all".equals(scope) && projectAccessService.isAdmin())) {
            List<ProjectMember> members = projectMemberMapper.selectList(
                    new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getUserId, userId));
            if (members.isEmpty()) {
                return PageResult.of(List.of(), page, size, 0);
            }
            wrapper.in(Project::getId, members.stream().map(ProjectMember::getProjectId).toList());
        }
        wrapper.orderByDesc(Project::getCreatedAt);
        Page<Project> mpPage = projectMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (Project p : mpPage.getRecords()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("status", p.getStatus());
            row.put("deadline", p.getDeadline());
            row.put("coverAssetId", p.getCoverAssetId());
            row.put("memberCount", projectMemberMapper.selectCount(
                    new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, p.getId())));
            row.put("assetCount", assetMapper.selectCount(
                    new LambdaQueryWrapper<com.platform.agent.asset.entity.Asset>()
                            .eq(com.platform.agent.asset.entity.Asset::getProjectId, p.getId())));
            row.put("createdAt", p.getCreatedAt());
            records.add(row);
        }
        return PageResult.of(records, page, size, mpPage.getTotal());
    }

    /** 项目详情：含简报与成员数。 */
    public Map<String, Object> detail(String projectId) {
        projectAccessService.requireVisible(projectId);
        Project project = getProject(projectId);
        CreativeBrief brief = creativeBriefMapper.selectOne(
                new LambdaQueryWrapper<CreativeBrief>().eq(CreativeBrief::getProjectId, projectId));

        Map<String, Object> data = new HashMap<>();
        data.put("id", project.getId());
        data.put("tenantId", project.getTenantId());
        data.put("name", project.getName());
        data.put("status", project.getStatus());
        data.put("deadline", project.getDeadline());
        data.put("coverAssetId", project.getCoverAssetId());
        data.put("brief", brief);
        data.put("memberCount", projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId)));
        data.put("assetCount", assetMapper.selectCount(
                new LambdaQueryWrapper<com.platform.agent.asset.entity.Asset>()
                        .eq(com.platform.agent.asset.entity.Asset::getProjectId, projectId)));
        data.put("createdAt", project.getCreatedAt());
        return data;
    }

    /** 更新项目基本信息（OWNER/EDITOR）。 */
    public Project update(String projectId, ProjectUpdateRequest request) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        Project project = getProject(projectId);
        if (request.getName() != null) {
            project.setName(request.getName());
        }
        if (request.getCoverAssetId() != null) {
            project.setCoverAssetId(request.getCoverAssetId());
        }
        if (request.getDeadline() != null) {
            project.setDeadline(request.getDeadline());
        }
        projectMapper.updateById(project);
        return project;
    }

    /** 归档 / 恢复归档（OWNER；管理员旁路）。 */
    public void setArchived(String projectId, boolean archived) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER);
        Project project = getProject(projectId);
        project.setStatus(archived ? Project.STATUS_ARCHIVED : Project.STATUS_ACTIVE);
        projectMapper.updateById(project);
    }

    /**
     * 项目时间线：任务 + 资产按时间合并排序。
     * 批量任务逐条返回，由前端按 batchGroupId 折叠分组。
     */
    public Map<String, Object> timeline(String projectId) {
        projectAccessService.requireVisible(projectId);

        List<Map<String, Object>> events = new ArrayList<>();

        List<AgentTask> tasks = agentTaskMapper.selectList(
                new LambdaQueryWrapper<AgentTask>()
                        .eq(AgentTask::getProjectId, projectId)
                        .orderByAsc(AgentTask::getCreatedAt));
        for (AgentTask t : tasks) {
            Map<String, Object> e = new HashMap<>();
            e.put("type", "TASK");
            e.put("id", t.getId());
            e.put("title", t.getWorkflowType());
            e.put("status", t.getStatus());
            e.put("createdAt", t.getCreatedAt());
            e.put("batchGroupId", t.getBatchGroupId());
            events.add(e);
        }

        List<com.platform.agent.asset.entity.Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<com.platform.agent.asset.entity.Asset>()
                        .eq(com.platform.agent.asset.entity.Asset::getProjectId, projectId)
                        .orderByAsc(com.platform.agent.asset.entity.Asset::getCreatedAt));
        for (com.platform.agent.asset.entity.Asset a : assets) {
            Map<String, Object> e = new HashMap<>();
            e.put("type", "ASSET");
            e.put("id", a.getId());
            e.put("title", a.getTitle());
            e.put("assetType", a.getAssetType());
            e.put("derivationType", a.getDerivationType());
            e.put("status", a.getStatus());
            e.put("createdAt", a.getCreatedAt());
            e.put("parentAssetId", a.getParentAssetId());
            events.add(e);
        }

        events.sort((a, b) -> String.valueOf(a.get("createdAt")).compareTo(String.valueOf(b.get("createdAt"))));
        Map<String, Object> result = new HashMap<>();
        result.put("events", events);
        result.put("taskCount", tasks.size());
        result.put("assetCount", assets.size());
        return result;
    }

    // ========== 知识库挂载 ==========

    public List<ProjectDataset> listDatasets(String projectId) {
        projectAccessService.requireVisible(projectId);
        return projectDatasetMapper.selectList(
                new LambdaQueryWrapper<ProjectDataset>().eq(ProjectDataset::getProjectId, projectId));
    }

    public void bindDataset(String projectId, String datasetId) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        Long exists = projectDatasetMapper.selectCount(new LambdaQueryWrapper<ProjectDataset>()
                .eq(ProjectDataset::getProjectId, projectId)
                .eq(ProjectDataset::getDatasetId, datasetId));
        if (exists > 0) {
            return; // 幂等
        }
        ProjectDataset pd = new ProjectDataset();
        pd.setProjectId(projectId);
        pd.setDatasetId(datasetId);
        projectDatasetMapper.insert(pd);
    }

    public void unbindDataset(String projectId, String datasetId) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
        projectDatasetMapper.delete(new LambdaQueryWrapper<ProjectDataset>()
                .eq(ProjectDataset::getProjectId, projectId)
                .eq(ProjectDataset::getDatasetId, datasetId));
    }

    // ========== 内部工具 ==========

    public Project getProject(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    /** 校验任务提交时的项目归属（供任务域调用）。 */
    public void checkProjectWritable(String projectId) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR);
    }
}
