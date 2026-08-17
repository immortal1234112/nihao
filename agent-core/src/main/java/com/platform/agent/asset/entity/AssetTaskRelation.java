package com.platform.agent.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 资产-任务溯源关系 —— 完整事实来源（parent 链只是展示优化）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("asset_task_relation")
public class AssetTaskRelation extends BaseEntity {

    @TableField("asset_id")
    private String assetId;

    @TableField("task_id")
    private String taskId;

    @TableField("relation_type")
    private String relationType;

    // ========== 关系类型常量 ==========
    public static final String REL_INPUT = "INPUT";
    public static final String REL_OUTPUT = "OUTPUT";
}
