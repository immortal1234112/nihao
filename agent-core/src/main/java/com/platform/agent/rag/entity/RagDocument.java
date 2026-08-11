package com.platform.agent.rag.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 文档实体。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("rag_document")
public class RagDocument extends BaseEntity {

    @TableField("dataset_id")
    private String datasetId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    /** txt|md */
    @TableField("file_type")
    private String fileType;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("parsed_at")
    private LocalDateTime parsedAt;

    // ========== 状态常量 ==========
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
}
