package com.platform.storagesvc.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 存储文件实体 —— 记录所有上传/生成的文件元数据。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("storage_file")
public class StorageFile extends BaseEntity {

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("original_name")
    private String originalName;

    @TableField("object_key")
    private String objectKey;

    @TableField("content_type")
    private String contentType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("file_md5")
    private String fileMd5;

    @TableField("width")
    private Integer width;

    @TableField("height")
    private Integer height;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("thumbnail_key")
    private String thumbnailKey;

    @TableField("provider")
    private String provider;

    @TableField("storage_class")
    private String storageClass;

    @TableField("visibility")
    private String visibility;

    @TableField("expire_at")
    private java.time.LocalDateTime expireAt;

    @TableField("source_task_id")
    private String sourceTaskId;

    @TableField("source_type")
    private String sourceType;

    // ========== 常量 ==========
    public static final String PROVIDER_MINIO = "minio";

    public static final String CLASS_TEMP = "TEMP";
    public static final String CLASS_ASSET = "ASSET";
    public static final String CLASS_ARCHIVE = "ARCHIVE";

    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_TENANT = "TENANT";

    public static final String SOURCE_IMAGE_GEN = "IMAGE_GEN";
    public static final String SOURCE_VIDEO_GEN = "VIDEO_GEN";
    public static final String SOURCE_UPLOAD = "UPLOAD";
}
