package com.platform.agent.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加项目成员请求。
 */
@Data
public class MemberAddRequest {

    @NotBlank(message = "userId 不能为空")
    private String userId;

    @NotBlank(message = "roleCode 不能为空")
    private String roleCode;
}
