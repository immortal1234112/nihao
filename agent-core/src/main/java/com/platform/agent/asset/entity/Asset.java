package com.platform.agent.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 内容资产实体 —— 项目化创作的沉淀物，支持派生链追溯与软删除（链不剪断）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("asset")
public class Asset extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    /** NULL=租户级素材库 */
    @TableField("project_id")
    private String projectId;

    /** 派生链：直接来源资产 */
    @TableField("parent_asset_id")
    private String parentAssetId;

    @TableField("derivation_type")
    private String derivationType;

    @TableField("asset_type")
    private String assetType;

    @TableField("title")
    private String title;

    @TableField("description")
    private String description;

    /** storage_service.storage_file.id，TEXT 资产可为空 */
    @TableField("file_id")
    private String fileId;

    /** TEXT 资产正文 */
    @TableField("content_text")
    private String contentText;

    @TableField("thumb_file_id")
    private String thumbFileId;

    @TableField("prompt")
    private String prompt;

    @TableField("model_provider")
    private String modelProvider;

    @TableField("model_code")
    private String modelCode;

    @TableField("params")
    private String params;

    @TableField("status")
    private String status;

    @TableField("starred")
    private Integer starred;

    /** 软删除：列表默认不可见，派生链引用保留 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField("created_by")
    private String createdBy;

    // ========== 派生类型常量 ==========
    public static final String DERIVATION_UPLOAD = "UPLOAD";
    public static final String DERIVATION_IMPORT = "IMPORT";
    public static final String DERIVATION_GENERATED = "GENERATED";
    public static final String DERIVATION_VARIANT = "VARIANT";
    public static final String DERIVATION_REGEN = "REGEN";
    public static final String DERIVATION_EDIT = "EDIT";
    public static final String DERIVATION_CONVERT = "CONVERT";
    public static final String DERIVATION_EXPORT = "EXPORT";

    // ========== 类型常量 ==========
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_AUDIO = "AUDIO";
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_FILE = "FILE";

    // ========== 审核状态常量 ==========
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_UNDER_REVIEW = "UNDER_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}
