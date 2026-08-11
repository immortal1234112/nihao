package com.platform.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索引用（单条命中分块）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagReference {

    private String chunkId;

    private String content;

    /** 余弦相似度 0~1 */
    private double similarity;

    private String documentId;

    private String documentName;
}
