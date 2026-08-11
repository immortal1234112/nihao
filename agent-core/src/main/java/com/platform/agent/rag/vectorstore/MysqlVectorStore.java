package com.platform.agent.rag.vectorstore;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.rag.entity.RagChunk;
import com.platform.agent.rag.entity.RagDocument;
import com.platform.agent.rag.mapper.RagChunkMapper;
import com.platform.agent.rag.mapper.RagDocumentMapper;
import com.platform.agent.rag.service.VectorSimilarity;
import com.platform.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MySQL JSON 列向量存储实现。
 * 检索：候选集（按租户+数据集过滤，上限可配）→ 应用层余弦相似度 → topK。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlVectorStore implements VectorStore {

    private final RagChunkMapper chunkMapper;
    private final RagDocumentMapper documentMapper;

    @Value("${rag.search.max-candidates:2000}")
    private int maxCandidates;

    @Override
    public void saveChunk(RagChunk chunk, List<Double> embedding) {
        chunk.setEmbeddingJson(JsonUtils.toJson(embedding));
        chunkMapper.insert(chunk);
    }

    @Override
    public void deleteChunks(String datasetId, String documentId) {
        chunkMapper.delete(new LambdaQueryWrapper<RagChunk>()
                .eq(RagChunk::getDatasetId, datasetId)
                .eq(RagChunk::getDocumentId, documentId));
    }

    @Override
    public List<ScoredChunk> search(String tenantId, String datasetId, List<Double> queryVector, int topK) {
        List<RagChunk> candidates = chunkMapper.selectList(
                new LambdaQueryWrapper<RagChunk>()
                        .eq(RagChunk::getTenantId, tenantId)
                        .eq(RagChunk::getDatasetId, datasetId)
                        .isNotNull(RagChunk::getEmbeddingJson)
                        .orderByAsc(RagChunk::getChunkIndex)
                        .last("LIMIT " + Math.max(1, maxCandidates))
        );
        if (candidates.isEmpty()) {
            return List.of();
        }

        double[] query = VectorSimilarity.toArray(queryVector);
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (RagChunk chunk : candidates) {
            List<Double> embedding = parseEmbedding(chunk.getEmbeddingJson());
            if (embedding == null || embedding.isEmpty()) continue;
            double similarity = VectorSimilarity.cosine(query, VectorSimilarity.toArray(embedding));
            scored.add(new ScoredChunk(chunk.getId(), chunk.getContent(), similarity,
                    chunk.getDocumentId(), null));
        }
        scored.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        List<ScoredChunk> top = scored.subList(0, Math.min(topK, scored.size()));

        // 一次查询组装文档名
        Set<String> docIds = top.stream().map(ScoredChunk::documentId).collect(Collectors.toSet());
        Map<String, String> docNames = new HashMap<>();
        if (!docIds.isEmpty()) {
            List<RagDocument> docs = documentMapper.selectList(
                    new LambdaQueryWrapper<RagDocument>().in(RagDocument::getId, docIds));
            for (RagDocument doc : docs) {
                docNames.put(doc.getId(), doc.getFileName());
            }
        }
        return top.stream()
                .map(s -> new ScoredChunk(s.chunkId(), s.content(), s.similarity(),
                        s.documentId(), docNames.getOrDefault(s.documentId(), "未知文档")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Double> parseEmbedding(String json) {
        try {
            return (List<Double>) (Object) JsonUtils.fromJson(json, List.class);
        } catch (Exception e) {
            log.warn("Parse embedding failed, skip chunk: {}", e.getMessage());
            return null;
        }
    }
}
