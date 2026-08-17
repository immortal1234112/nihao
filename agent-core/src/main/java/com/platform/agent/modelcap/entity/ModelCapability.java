package com.platform.agent.modelcap.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型能力目录（第一阶段最小版）—— 能力 → provider/模型 → 档位/成本/优先级。
 * 完整路由（熔断/降级/自动切换）留待阶段 3。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("model_capability")
public class ModelCapability extends BaseEntity {

    @TableField("capability")
    private String capability;

    @TableField("provider")
    private String provider;

    @TableField("model_code")
    private String modelCode;

    @TableField("label")
    private String label;

    @TableField("tier")
    private String tier;

    @TableField("cost_unit")
    private String costUnit;

    @TableField("cost_per_unit")
    private BigDecimal costPerUnit;

    @TableField("enabled")
    private Integer enabled;

    @TableField("priority")
    private Integer priority;

    // ========== 能力常量 ==========
    public static final String CAP_TEXT_CHAT = "TEXT_CHAT";
    public static final String CAP_TEXT_GEN = "TEXT_GEN";
    public static final String CAP_TEXT2IMAGE = "TEXT2IMAGE";
    public static final String CAP_IMAGE2IMAGE = "IMAGE2IMAGE";
    public static final String CAP_TEXT2VIDEO = "TEXT2VIDEO";
    public static final String CAP_IMAGE2VIDEO = "IMAGE2VIDEO";
    public static final String CAP_EMBEDDING = "EMBEDDING";

    // ========== 档位常量 ==========
    public static final String TIER_FAST = "FAST";
    public static final String TIER_STANDARD = "STANDARD";
    public static final String TIER_HIGH_QUALITY = "HIGH_QUALITY";
}
