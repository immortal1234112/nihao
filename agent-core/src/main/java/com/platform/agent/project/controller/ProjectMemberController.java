package com.platform.agent.project.controller;

import com.platform.agent.project.dto.MemberAddRequest;
import com.platform.agent.project.dto.MemberRoleRequest;
import com.platform.agent.project.entity.ProjectMember;
import com.platform.agent.project.service.ProjectMemberService;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目成员控制器 — /api/v1/projects/{id}/members
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    @GetMapping
    public Result<List<ProjectMember>> list(@PathVariable String projectId) {
        return Result.ok(projectMemberService.list(projectId));
    }

    @AuditLog(action = "ADD_PROJECT_MEMBER")
    @PostMapping
    public Result<ProjectMember> add(@PathVariable String projectId,
                                     @Valid @RequestBody MemberAddRequest request) {
        return Result.ok(projectMemberService.add(projectId, request));
    }

    @AuditLog(action = "UPDATE_MEMBER_ROLE")
    @PutMapping("/{memberId}/role")
    public Result<Void> updateRole(@PathVariable String projectId,
                                   @PathVariable String memberId,
                                   @Valid @RequestBody MemberRoleRequest request) {
        projectMemberService.updateRole(projectId, memberId, request.getRoleCode());
        return Result.ok();
    }

    @AuditLog(action = "REMOVE_PROJECT_MEMBER")
    @DeleteMapping("/{memberId}")
    public Result<Void> remove(@PathVariable String projectId, @PathVariable String memberId) {
        projectMemberService.remove(projectId, memberId);
        return Result.ok();
    }
}
