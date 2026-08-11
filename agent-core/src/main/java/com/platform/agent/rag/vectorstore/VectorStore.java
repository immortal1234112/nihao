package com.platform.agent.rag.vectorstore;

import com.platform.agent.rag.entity.RagChunk;

import java.util.List;

/**
 * 向量存储抽象 —— 当前为 MySQL JSON 列 + 应用层余弦检索（轻量），
 * 未来可增加 MilvusVectorStore 实现（如 pgvector/Milvus）而无侵入替换。
 */
public interface VectorStore {

    /** 保存分块（embedding 写入 chunk.embeddingJson 后入库） */
    void saveChunk(RagChunk chunk, List<Double> embedding);

    /** 删除某文档的所有分块 */
    void deleteChunks(String datasetId, String documentId);

    /** 检索 topK 个最相似分块（按租户+数据集隔离） */
    List<ScoredChunk> search(String tenantId, String datasetId, List<Double> queryVector, int topK);
}
