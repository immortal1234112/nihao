package com.platform.agent.rag.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * RAG 知识库实体。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("rag_dataset")
public class RagDataset extends BaseEntity {

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("user_id")
    private String userId;

    /** embedding 提供商（创建后不可换，换需重建知识库） */
    @TableField("embedding_provider")
    private String embeddingProvider;

    @TableField("embedding_model")
    private String embeddingModel;

    /** 向量维度 */
    @TableField("dimension")
    private Integer dimension;

    @TableField("doc_count")
    private Integer docCount;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("status")
    private String status;

    // ========== 状态常量 ==========
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
}
