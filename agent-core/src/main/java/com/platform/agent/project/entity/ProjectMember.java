package com.platform.agent.project.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 项目成员实体 —— 项目级权限的唯一事实源（project 表无 owner_id 列）。
 * 任何时刻项目至少有一名 OWNER（服务层保证）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("project_member")
public class ProjectMember extends BaseEntity {

    @TableField("project_id")
    private String projectId;

    @TableField("user_id")
    private String userId;

    @TableField("role_code")
    private String roleCode;

    // ========== 角色常量 ==========
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_REVIEWER = "REVIEWER";
    public static final String ROLE_VIEWER = "VIEWER";
}
