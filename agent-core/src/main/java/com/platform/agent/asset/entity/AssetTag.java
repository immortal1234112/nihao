package com.platform.agent.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 资产标签 —— 租户内去重，字典化留待资产中心优化阶段。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("asset_tag")
public class AssetTag extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("asset_id")
    private String assetId;

    @TableField("name")
    private String name;
}
