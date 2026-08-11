package com.platform.agent.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.rag.adapter.embedding.EmbeddingAdapter;
import com.platform.agent.rag.entity.RagChunk;
import com.platform.agent.rag.entity.RagDataset;
import com.platform.agent.rag.entity.RagDocument;
import com.platform.agent.rag.mapper.RagChunkMapper;
import com.platform.agent.rag.mapper.RagDatasetMapper;
import com.platform.agent.rag.mapper.RagDocumentMapper;
import com.platform.agent.rag.vectorstore.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 文档异步解析流水线：分块 → embedding（批次级重试）→ 入库 → 状态/计数更新。
 * 所有数据（tenantId、embeddingProvider）从数据库行读取，禁止依赖 TenantContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagDocumentProcessor {

    private final RagDocumentMapper documentMapper;
    private final RagDatasetMapper datasetMapper;
    private final RagChunkMapper chunkMapper;
    private final ChunkingService chunkingService;
    private final VectorStore vectorStore;
    private final List<EmbeddingAdapter> embeddingAdapters;

    @Value("${rag.chunk-size:500}")
    private int chunkSize;

    @Value("${rag.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${rag.embedding-batch-size:32}")
    private int embeddingBatchSize;

    @Value("${rag.embedding-max-retries:2}")
    private int maxRetries;

    /** 新上传文档解析 */
    @Async("ragExecutor")
    public void processAsync(String documentId, String text) {
        doProcess(documentId, text);
    }

    /** FAILED 文档重试：从已有分块拼接原文后重新解析 */
    @Async("ragExecutor")
    public void reprocessAsync(String documentId) {
        RagDocument doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        List<RagChunk> existing = chunkMapper.selectList(
                new LambdaQueryWrapper<RagChunk>()
                        .eq(RagChunk::getDocumentId, documentId)
                        .orderByAsc(RagChunk::getChunkIndex));
        String text = existing.stream().map(RagChunk::getContent).collect(Collectors.joining("\n"));
        if (text.isBlank()) {
            fail(doc, "无可用分块内容");
            return;
        }
        vectorStore.deleteChunks(doc.getDatasetId(), documentId);
        doProcess(documentId, text);
    }

    private void doProcess(String documentId, String text) {
        RagDocument doc = documentMapper.selectById(documentId);
        if (doc == null) return;
        if (!RagDocument.STATUS_PARSING.equals(doc.getStatus())) {
            log.info("RAG document skipped (status={}): {}", doc.getStatus(), documentId);
            return;
        }
        RagDataset dataset = datasetMapper.selectById(doc.getDatasetId());
        if (dataset == null) {
            fail(doc, "知识库不存在");
            return;
        }
        EmbeddingAdapter adapter = resolveAdapter(dataset.getEmbeddingProvider());
        if (adapter == null) {
            fail(doc, "embedding 提供商未注册: " + dataset.getEmbeddingProvider());
            return;
        }

        try {
            List<String> chunks = chunkingService.chunk(text, chunkSize, chunkOverlap);
            int inserted = 0;
            for (int start = 0; start < chunks.size(); start += embeddingBatchSize) {
                List<String> batch = chunks.subList(start, Math.min(start + embeddingBatchSize, chunks.size()));
                List<List<Double>> embeddings = embedWithRetry(adapter, batch);
                for (int i = 0; i < batch.size(); i++) {
                    RagChunk chunk = new RagChunk();
                    chunk.setDatasetId(doc.getDatasetId());
                    chunk.setDocumentId(doc.getId());
                    chunk.setTenantId(doc.getTenantId());
                    chunk.setChunkIndex(start + i);
                    chunk.setContent(batch.get(i));
                    chunk.setTokenCount(batch.get(i).length());
                    vectorStore.saveChunk(chunk, embeddings.get(i));
                    inserted++;
                }
            }

            doc.setStatus(RagDocument.STATUS_READY);
            doc.setChunkCount(inserted);
            doc.setParsedAt(LocalDateTime.now());
            doc.setErrorMsg(null);
            documentMapper.updateById(doc);
            log.info("RAG document parsed: docId={}, chunks={}", documentId, inserted);
        } catch (Exception e) {
            log.error("RAG document parse failed: docId={}", documentId, e);
            fail(doc, e.getMessage());
        }
        refreshCounts(doc.getDatasetId());
    }

    private EmbeddingAdapter resolveAdapter(String provider) {
        if (provider == null) return null;
        return embeddingAdapters.stream()
                .filter(a -> a.provider().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
    }

    private List<List<Double>> embedWithRetry(EmbeddingAdapter adapter, List<String> batch) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return adapter.embedBatch(batch);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Embedding batch failed (attempt {}): {}", attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastError != null ? lastError : new RuntimeException("Embedding 失败");
    }

    private void fail(RagDocument doc, String message) {
        doc.setStatus(RagDocument.STATUS_FAILED);
        doc.setErrorMsg(message != null ? message.substring(0, Math.min(500, message.length())) : "解析失败");
        documentMapper.updateById(doc);
        refreshCounts(doc.getDatasetId());
    }

    private void refreshCounts(String datasetId) {
        RagDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) return;
        Long docCount = documentMapper.selectCount(new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getDatasetId, datasetId)
                .eq(RagDocument::getStatus, RagDocument.STATUS_READY));
        Long chunkCount = chunkMapper.selectCount(new LambdaQueryWrapper<RagChunk>()
                .eq(RagChunk::getDatasetId, datasetId));
        dataset.setDocCount(docCount.intValue());
        dataset.setChunkCount(chunkCount.intValue());
        datasetMapper.updateById(dataset);
    }
}
