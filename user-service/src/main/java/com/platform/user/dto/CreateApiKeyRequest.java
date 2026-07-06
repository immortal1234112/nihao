package com.platform.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建 API Key 请求。
 */
@Data
public class CreateApiKeyRequest {

    @NotBlank(message = "名称不能为空")
    private String name;

    private Integer rateLimitQpm = 60;

    private LocalDateTime expiresAt;
}
