package com.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新 Token 请求。
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    /** Access Token（可选）：登出时一并拉黑，撤销当前会话的 access token */
    private String accessToken;
}
