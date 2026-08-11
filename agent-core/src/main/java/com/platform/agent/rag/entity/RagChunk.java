package com.platform.agent.rag.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * RAG 分块实体。
 * embedding 为 JSON 数组，实体侧用 String 承载（与 agent_task.request_params 同风格），
 * 读写走 JsonUtils，检索在应用层做余弦相似度。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("rag_chunk")
public class RagChunk extends BaseEntity {

    @TableField("dataset_id")
    private String datasetId;

    @TableField("document_id")
    private String documentId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("content")
    private String content;

    @TableField("embedding")
    private String embeddingJson;

    @TableField("token_count")
    private Integer tokenCount;
}
