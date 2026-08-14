package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 提示词模板 —— 供创作页一键填充提示词。
 * scope：PUBLIC（全平台可见）/ TENANT（租户内）/ PRIVATE（仅本人）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("prompt_template")
public class PromptTemplate extends BaseEntity {

    @TableField("name")
    private String name;

    @TableField("category")
    private String category;

    @TableField("engine_type")
    private String engineType;

    @TableField("prompt")
    private String prompt;

    @TableField("negative_prompt")
    private String negativePrompt;

    @TableField("scope")
    private String scope;

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("usage_count")
    private Integer usageCount;

    // ========== scope 常量 ==========
    public static final String SCOPE_PUBLIC = "PUBLIC";
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_PRIVATE = "PRIVATE";
}
