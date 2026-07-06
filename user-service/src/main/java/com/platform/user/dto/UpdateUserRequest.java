package com.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新用户信息请求。
 */
@Data
public class UpdateUserRequest {

    private String email;

    private String phone;

    private String avatarUrl;
}
