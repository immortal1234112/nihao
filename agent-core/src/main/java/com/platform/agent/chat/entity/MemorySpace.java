package com.platform.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 记忆空间实体 —— 一套独立的「长期 + 短期」记忆的分组容器。
 * <p>
 * 记忆按 (user_id, tenant_id, space_id) 隔离：同一用户可建多个空间（工作/写作/个人…），
 * 对话创建时绑定一个空间，该对话的记忆注入与提取都限定在该空间内，互不污染。
 * <p>
 * 每个用户自动拥有一个默认空间（{@code is_default=1}），改造前的存量记忆全部归入默认空间，
 * 老对话（space_id 为 NULL）运行时按默认空间解析，行为与改造前一致。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("memory_space")
public class MemorySpace extends BaseEntity {

    /** 默认空间名（每用户唯一，不可删） */
    public static final String DEFAULT_NAME = "默认";

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    /** 1 = 默认空间（每用户唯一，不可删） */
    @TableField("is_default")
    private Integer isDefault;

    @TableField("sort_order")
    private Integer sortOrder;

    /**
     * 软删标记（0 正常 / 1 已删）。
     * 不用 @TableLogic：删除空间时其下记忆也要软删，且需要能查到已删空间做归属校验。
     */
    @TableField("deleted")
    private Integer deleted;
}
