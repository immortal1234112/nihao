package com.platform.agent.rag.dto;

import lombok.Data;

/**
 * 分块视图（不含 embedding 向量）。
 */
@Data
public class RagChunkVO {

    private String id;

    private Integer chunkIndex;

    private String content;

    private String documentId;

    private String documentName;
}
