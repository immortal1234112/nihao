package com.platform.agent.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.project.dto.MemberAddRequest;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.mapper.ProjectMemberMapper;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.feign.UserFeignClient;
import com.platform.web.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 项目成员服务 —— 成员增删、角色变更，维护"≥1 OWNER"不变量。
 */
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    public static final Set<String> VALID_ROLES = Set.of(
            ProjectMember.ROLE_OWNER, ProjectMember.ROLE_EDITOR,
            ProjectMember.ROLE_REVIEWER, ProjectMember.ROLE_VIEWER);

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectAccessService projectAccessService;
    private final UserFeignClient userFeignClient;

    public List<ProjectMember> list(String projectId) {
        projectAccessService.requireVisible(projectId);
        return projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .orderByAsc(ProjectMember::getCreatedAt));
    }

    /** 添加成员：必须是租户成员；角色合法；不可重复添加。 */
    public ProjectMember add(String projectId, MemberAddRequest request) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER);
        validateRole(request.getRoleCode());

        // 不变量 ①：成员必须先存在于该租户
        Result<Boolean> check = userFeignClient.isTenantMember(request.getUserId(), TenantContext.get());
        if (check == null || check.getCode() != 0 || !Boolean.TRUE.equals(check.getData())) {
            throw new BizException(ErrorCode.MEMBER_NOT_IN_TENANT);
        }

        Long exists = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, request.getUserId()));
        if (exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该用户已是项目成员");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(request.getUserId());
        member.setRoleCode(request.getRoleCode());
        projectMemberMapper.insert(member);
        return member;
    }

    /** 变更角色：最后一名 OWNER 不可降级。 */
    public void updateRole(String projectId, String memberId, String roleCode) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER);
        validateRole(roleCode);
        ProjectMember member = getMember(projectId, memberId);

        if (ProjectMember.ROLE_OWNER.equals(member.getRoleCode())
                && !ProjectMember.ROLE_OWNER.equals(roleCode)) {
            checkLastOwner(projectId);
        }
        member.setRoleCode(roleCode);
        projectMemberMapper.updateById(member);
    }

    /** 移除成员：最后一名 OWNER 不可移除。 */
    public void remove(String projectId, String memberId) {
        projectAccessService.requireProjectRole(projectId, ProjectMember.ROLE_OWNER);
        ProjectMember member = getMember(projectId, memberId);
        if (ProjectMember.ROLE_OWNER.equals(member.getRoleCode())) {
            checkLastOwner(projectId);
        }
        projectMemberMapper.deleteById(memberId);
    }

    // ========== 内部工具 ==========

    private void checkLastOwner(String projectId) {
        Long ownerCount = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getRoleCode, ProjectMember.ROLE_OWNER));
        if (ownerCount != null && ownerCount <= 1) {
            throw new BizException(ErrorCode.LAST_OWNER_PROTECTED);
        }
    }

    private void validateRole(String roleCode) {
        if (!VALID_ROLES.contains(roleCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法角色: " + roleCode);
        }
    }

    private ProjectMember getMember(String projectId, String memberId) {
        ProjectMember member = projectMemberMapper.selectById(memberId);
        if (member == null || !projectId.equals(member.getProjectId())) {
            throw new BizException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return member;
    }
}
