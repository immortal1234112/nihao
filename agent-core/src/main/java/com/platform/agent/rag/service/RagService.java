package com.platform.agent.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.rag.adapter.embedding.EmbeddingAdapter;
import com.platform.agent.rag.dto.RagChunkVO;
import com.platform.agent.rag.dto.RagDatasetCreateRequest;
import com.platform.agent.rag.dto.RagDatasetUpdateRequest;
import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.entity.RagChunk;
import com.platform.agent.rag.entity.RagDataset;
import com.platform.agent.rag.entity.RagDocument;
import com.platform.agent.rag.mapper.RagChunkMapper;
import com.platform.agent.rag.mapper.RagDatasetMapper;
import com.platform.agent.rag.mapper.RagDocumentMapper;
import com.platform.agent.rag.vectorstore.ScoredChunk;
import com.platform.agent.rag.vectorstore.VectorStore;
import com.platform.core.base.PageResult;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 核心服务 —— 数据集 CRUD、文档上传/重试、检索、引用拼接。
 * 检索结果供四路复用：/rag/search 测试检索、LLM Chat 增强、任务提示词增强、工作流 RAG_RETRIEVE 步骤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final List<EmbeddingAdapter> embeddingAdapters;
    private final RagDatasetMapper datasetMapper;
    private final RagDocumentMapper documentMapper;
    private final RagChunkMapper chunkMapper;
    private final VectorStore vectorStore;
    private final DocumentParseService documentParseService;
    private final RagDocumentProcessor documentProcessor;

    @Value("${rag.search-topk-limit:10}")
    private int topKLimit;

    // ==================== 数据集 ====================

    @Transactional
    public RagDataset createDataset(String userId, String tenantId, RagDatasetCreateRequest request) {
        String provider = request.getEmbeddingProvider() != null && !request.getEmbeddingProvider().isBlank()
                ? request.getEmbeddingProvider().trim()
                : "qwen-embedding";
        EmbeddingAdapter adapter = resolveAdapter(provider);

        RagDataset dataset = new RagDataset();
        dataset.setName(request.getName().trim());
        dataset.setDescription(request.getDescription() != null ? request.getDescription() : "");
        dataset.setTenantId(tenantId);
        dataset.setUserId(userId);
        dataset.setEmbeddingProvider(provider);
        dataset.setEmbeddingModel(request.getEmbeddingModel() != null && !request.getEmbeddingModel().isBlank()
                ? request.getEmbeddingModel().trim() : adapter.modelName());
        dataset.setDimension(adapter.dimension());
        dataset.setDocCount(0);
        dataset.setChunkCount(0);
        dataset.setStatus(RagDataset.STATUS_ACTIVE);
        datasetMapper.insert(dataset);
        log.info("RAG dataset created: id={}, provider={}", dataset.getId(), provider);
        return dataset;
    }

    public RagDataset getDataset(String datasetId, String userId, String tenantId) {
        return getOwnedDataset(datasetId, userId, tenantId);
    }

    public PageResult<RagDataset> listDatasets(String userId, String tenantId, int page, int size) {
        Page<RagDataset> mpPage = new Page<>(page, size);
        Page<RagDataset> result = datasetMapper.selectPage(mpPage, new LambdaQueryWrapper<RagDataset>()
                .eq(RagDataset::getUserId, userId)
                .eq(RagDataset::getTenantId, tenantId)
                .orderByDesc(RagDataset::getCreatedAt));
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    @Transactional
    public void updateDataset(String datasetId, String userId, String tenantId, RagDatasetUpdateRequest request) {
        RagDataset dataset = getOwnedDataset(datasetId, userId, tenantId);
        if (request.getName() != null && !request.getName().isBlank()) {
            dataset.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            dataset.setDescription(request.getDescription());
        }
        datasetMapper.updateById(dataset);
    }

    @Transactional
    public void deleteDataset(String datasetId, String userId, String tenantId) {
        getOwnedDataset(datasetId, userId, tenantId);
        chunkMapper.delete(new LambdaQueryWrapper<RagChunk>().eq(RagChunk::getDatasetId, datasetId));
        documentMapper.delete(new LambdaQueryWrapper<RagDocument>().eq(RagDocument::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
        log.info("RAG dataset deleted: id={}", datasetId);
    }

    // ==================== 文档 ====================

    @Transactional
    public RagDocument uploadDocument(String datasetId, String userId, String tenantId, MultipartFile file) {
        getOwnedDataset(datasetId, userId, tenantId);  // 归属校验
        DocumentParseService.ParsedText parsed = documentParseService.parse(file);

        RagDocument doc = new RagDocument();
        doc.setDatasetId(datasetId);
        doc.setTenantId(tenantId);
        doc.setFileName(parsed.fileName());
        doc.setFileSize(file.getSize());
        doc.setFileType(parsed.fileType());
        doc.setChunkCount(0);
        doc.setStatus(RagDocument.STATUS_PARSING);
        documentMapper.insert(doc);

        // 异步解析：必须在事务提交后触发，否则异步线程读不到未提交的行（静默跳过）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentProcessor.processAsync(doc.getId(), parsed.text());
            }
        });
        return doc;
    }

    public PageResult<RagDocument> listDocuments(String datasetId, String userId, String tenantId, int page, int size) {
        getOwnedDataset(datasetId, userId, tenantId);
        Page<RagDocument> mpPage = new Page<>(page, size);
        Page<RagDocument> result = documentMapper.selectPage(mpPage, new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getDatasetId, datasetId)
                .eq(RagDocument::getTenantId, tenantId)
                .orderByDesc(RagDocument::getCreatedAt));
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    @Transactional
    public void deleteDocument(String datasetId, String docId, String userId, String tenantId) {
        getOwnedDataset(datasetId, userId, tenantId);
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getDatasetId().equals(datasetId)) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        vectorStore.deleteChunks(datasetId, docId);
        documentMapper.deleteById(docId);
        refreshCounts(datasetId);
    }

    public void reparse(String docId, String userId, String tenantId) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        getOwnedDataset(doc.getDatasetId(), userId, tenantId);
        if (!RagDocument.STATUS_FAILED.equals(doc.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅 FAILED 文档可重试");
        }
        doc.setStatus(RagDocument.STATUS_PARSING);
        doc.setErrorMsg(null);
        documentMapper.updateById(doc);
        documentProcessor.reprocessAsync(doc.getId());
    }

    public PageResult<RagChunkVO> listChunks(String docId, String tenantId, int page, int size) {
        RagDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        Page<RagChunk> mpPage = new Page<>(page, size);
        Page<RagChunk> result = chunkMapper.selectPage(mpPage, new LambdaQueryWrapper<RagChunk>()
                .eq(RagChunk::getDocumentId, docId)
                .eq(RagChunk::getTenantId, tenantId)
                .orderByAsc(RagChunk::getChunkIndex));
        List<RagChunkVO> vos = result.getRecords().stream().map(c -> {
            RagChunkVO vo = new RagChunkVO();
            vo.setId(c.getId());
            vo.setChunkIndex(c.getChunkIndex());
            vo.setContent(c.getContent());
            vo.setDocumentId(c.getDocumentId());
            vo.setDocumentName(doc.getFileName());
            return vo;
        }).toList();
        return PageResult.of(vos, page, size, result.getTotal());
    }

    // ==================== 检索 ====================

    /**
     * 检索（同步）。供 /rag/search、LLM Chat、任务增强、工作流步骤复用。
     * 注意：LLM Chat 等场景调用方需自行 catch 异常降级（RAG 是增强不是阻塞）。
     */
    public RagSearchResult search(String userId, String tenantId, RagSearchRequest request) {
        RagDataset dataset = getOwnedDataset(request.getDatasetId(), userId, tenantId);
        EmbeddingAdapter adapter = resolveAdapter(dataset.getEmbeddingProvider());
        int topK = request.getTopK() != null
                ? Math.min(Math.max(1, request.getTopK()), topKLimit)
                : 3;

        List<List<Double>> queryVectors = adapter.embedBatch(List.of(request.getQuery()));
        if (queryVectors.isEmpty()) {
            return new RagSearchResult(request.getQuery(), List.of());
        }
        List<ScoredChunk> hits = vectorStore.search(tenantId, dataset.getId(), queryVectors.get(0), topK);
        List<RagReference> refs = hits.stream()
                .map(h -> new RagReference(h.chunkId(), h.content(), h.similarity(),
                        h.documentId(), h.documentName()))
                .toList();
        return new RagSearchResult(request.getQuery(), refs);
    }

    /**
     * 将引用列表拼接为 [参考资料] 提示词段（注入 systemPrompt / 任务 prompt）。
     */
    public String buildReferencePrompt(List<RagReference> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n[参考资料]（请优先依据以下资料回答，引用时标注来源文档名）\n");
        for (int i = 0; i < refs.size(); i++) {
            RagReference r = refs.get(i);
            sb.append(i + 1).append(". [来源: ").append(r.getDocumentName()).append("] ")
                    .append(r.getContent()).append("\n");
        }
        return sb.toString();
    }

    // ==================== embedding 提供商 ====================

    public List<Map<String, Object>> listEmbeddingProviders() {
        return embeddingAdapters.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("provider", a.provider());
            m.put("model", a.modelName());
            m.put("dimension", a.dimension());
            m.put("available", a.healthCheck());
            return m;
        }).toList();
    }

    // ==================== 辅助方法 ====================

    private RagDataset getOwnedDataset(String datasetId, String userId, String tenantId) {
        RagDataset dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.DATASET_NOT_FOUND);
        }
        if (!dataset.getUserId().equals(userId) || !dataset.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return dataset;
    }

    private EmbeddingAdapter resolveAdapter(String provider) {
        EmbeddingAdapter adapter = embeddingAdapters.stream()
                .filter(a -> a.provider().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
        if (adapter == null) {
            throw new BizException(ErrorCode.MODEL_UNAVAILABLE, "embedding 提供商未注册: " + provider);
        }
        return adapter;
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
