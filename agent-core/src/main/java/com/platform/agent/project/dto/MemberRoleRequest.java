package com.platform.agent.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改项目成员角色请求。
 */
@Data
public class MemberRoleRequest {

    @NotBlank(message = "roleCode 不能为空")
    private String roleCode;
}
