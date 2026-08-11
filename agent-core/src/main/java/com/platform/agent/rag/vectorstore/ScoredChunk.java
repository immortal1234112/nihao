package com.platform.agent.rag.vectorstore;

/**
 * 检索命中的分块（含相似度与文档信息）。
 */
public record ScoredChunk(String chunkId, String content, double similarity,
                          String documentId, String documentName) {
}
