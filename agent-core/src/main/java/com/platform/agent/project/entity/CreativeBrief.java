package com.platform.agent.project.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 创作简报实体 —— 每项目一份，为项目内创作提供统一语境。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("creative_brief")
public class CreativeBrief extends BaseEntity {

    @TableField("project_id")
    private String projectId;

    @TableField("target_audience")
    private String targetAudience;

    @TableField("theme")
    private String theme;

    @TableField("tone")
    private String tone;

    @TableField("channels")
    private String channels;

    @TableField("brand_words")
    private String brandWords;

    @TableField("banned_words")
    private String bannedWords;

    @TableField("extra")
    private String extra;
}
