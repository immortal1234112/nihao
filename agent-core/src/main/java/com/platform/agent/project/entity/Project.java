package com.platform.agent.project.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目实体 —— 创作的组织单元，归属租户。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("name")
    private String name;

    @TableField("cover_asset_id")
    private String coverAssetId;

    @TableField("status")
    private String status;

    @TableField("deadline")
    private LocalDateTime deadline;

    // ========== 状态常量 ==========
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
}
