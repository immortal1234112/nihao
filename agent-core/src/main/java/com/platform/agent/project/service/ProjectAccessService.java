package com.platform.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.project.entity.Project;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.mapper.ProjectMapper;
import com.platform.agent.project.mapper.ProjectMemberMapper;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 项目级权限统一强制点。
 * 合并规则：平台动作只看租户角色，项目动作只看项目角色，互不放大；
 * SUPER_ADMIN / TENANT_ADMIN 对租户内所有项目拥有全部权限（旁路）。
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_TENANT_ADMIN = "TENANT_ADMIN";

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;

    /**
     * 管理员旁路（租户作用域）：
     * SUPER_ADMIN 全局放行；TENANT_ADMIN 仅在其租户上下文内放行。
     * 注意：每个用户都是自己个人租户的 TENANT_ADMIN，若不做租户匹配，
     * 任意用户都会被旁路（越权读他租户项目）。
     */
    public boolean isAdmin() {
        return UserContext.getRoles().contains(ROLE_SUPER_ADMIN)
                || UserContext.getRoles().contains(ROLE_TENANT_ADMIN);
    }

    /** 当前用户是否对本项目所在租户拥有管理员角色（跨租户只认 SUPER_ADMIN）。 */
    private boolean isAdminOf(String projectTenantId) {
        if (UserContext.getRoles().contains(ROLE_SUPER_ADMIN)) {
            return true;
        }
        return UserContext.getRoles().contains(ROLE_TENANT_ADMIN)
                && TenantContext.get().equals(projectTenantId);
    }

    /**
     * 可见性校验：项目租户管理员旁路；成员返回其成员行；非成员抛"项目不存在"（防存在性泄露）。
     */
    public ProjectMember requireVisible(String projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        if (isAdminOf(project.getTenantId())) {
            return null;
        }
        ProjectMember member = selectMember(projectId);
        if (member == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return member;
    }

    /**
     * 项目角色校验：项目租户管理员旁路；成员角色不在允许集合内抛无权限。
     */
    public ProjectMember requireProjectRole(String projectId, String... allowedRoles) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        if (isAdminOf(project.getTenantId())) {
            return null;
        }
        ProjectMember member = requireVisible(projectId);
        for (String role : allowedRoles) {
            if (role.equals(member.getRoleCode())) {
                return member;
            }
        }
        throw new BizException(ErrorCode.NO_PERMISSION);
    }

    /** 当前用户在该项目的成员行（可能为 null）。 */
    public ProjectMember selectMember(String projectId) {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId));
    }
}
