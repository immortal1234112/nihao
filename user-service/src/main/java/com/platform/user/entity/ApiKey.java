package com.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * API Key 实体。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("api_key")
public class ApiKey extends BaseEntity {

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("key_hash")
    private String keyHash;

    @TableField("key_prefix")
    private String keyPrefix;

    @TableField("name")
    private String name;

    @TableField("rate_limit_qpm")
    private Integer rateLimitQpm;

    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("status")
    private String status;

    // ========== 状态常量 ==========
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
}
