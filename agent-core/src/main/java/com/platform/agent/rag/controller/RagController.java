package com.platform.agent.rag.controller;

import com.platform.agent.rag.dto.RagChunkVO;
import com.platform.agent.rag.dto.RagDatasetCreateRequest;
import com.platform.agent.rag.dto.RagDatasetUpdateRequest;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.entity.RagDataset;
import com.platform.agent.rag.entity.RagDocument;
import com.platform.agent.rag.service.RagService;
import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库控制器 — /api/v1/rag
 * 数据集管理、文档上传/解析、分块查看、测试检索。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    // ==================== 数据集 ====================

    @AuditLog(action = "RAG_CREATE_DATASET")
    @PostMapping("/datasets")
    public Result<RagDataset> createDataset(@Valid @RequestBody RagDatasetCreateRequest request) {
        return Result.ok(ragService.createDataset(UserContext.getUserId(), getTenantId(), request));
    }

    @GetMapping("/datasets")
    public Result<PageResult<RagDataset>> listDatasets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ragService.listDatasets(UserContext.getUserId(), getTenantId(), page, size));
    }

    @GetMapping("/datasets/{id}")
    public Result<RagDataset> getDataset(@PathVariable String id) {
        return Result.ok(ragService.getDataset(id, UserContext.getUserId(), getTenantId()));
    }

    @AuditLog(action = "RAG_UPDATE_DATASET")
    @PutMapping("/datasets/{id}")
    public Result<Void> updateDataset(@PathVariable String id, @RequestBody RagDatasetUpdateRequest request) {
        ragService.updateDataset(id, UserContext.getUserId(), getTenantId(), request);
        return Result.ok();
    }

    @AuditLog(action = "RAG_DELETE_DATASET")
    @DeleteMapping("/datasets/{id}")
    public Result<Void> deleteDataset(@PathVariable String id) {
        ragService.deleteDataset(id, UserContext.getUserId(), getTenantId());
        return Result.ok();
    }

    // ==================== 文档 ====================

    @AuditLog(action = "RAG_UPLOAD_DOCUMENT")
    @PostMapping("/datasets/{id}/documents")
    public Result<RagDocument> uploadDocument(@PathVariable String id,
                                              @RequestParam("file") MultipartFile file) {
        return Result.ok(ragService.uploadDocument(id, UserContext.getUserId(), getTenantId(), file));
    }

    @GetMapping("/datasets/{id}/documents")
    public Result<PageResult<RagDocument>> listDocuments(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ragService.listDocuments(id, UserContext.getUserId(), getTenantId(), page, size));
    }

    @AuditLog(action = "RAG_DELETE_DOCUMENT")
    @DeleteMapping("/datasets/{datasetId}/documents/{docId}")
    public Result<Void> deleteDocument(@PathVariable String datasetId, @PathVariable String docId) {
        ragService.deleteDocument(datasetId, docId, UserContext.getUserId(), getTenantId());
        return Result.ok();
    }

    @GetMapping("/documents/{docId}/chunks")
    public Result<PageResult<RagChunkVO>> listChunks(
            @PathVariable String docId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(ragService.listChunks(docId, getTenantId(), page, size));
    }

    @AuditLog(action = "RAG_REPARSE")
    @PostMapping("/documents/{docId}/reparse")
    public Result<Void> reparse(@PathVariable String docId) {
        ragService.reparse(docId, UserContext.getUserId(), getTenantId());
        return Result.ok();
    }

    // ==================== 检索 ====================

    @AuditLog(action = "RAG_SEARCH")
    @PostMapping("/search")
    public Result<RagSearchResult> search(@Valid @RequestBody RagSearchRequest request) {
        return Result.ok(ragService.search(UserContext.getUserId(), getTenantId(), request));
    }

    // ==================== embedding 提供商 ====================

    @GetMapping("/embedding-providers")
    public Result<List<Map<String, Object>>> listEmbeddingProviders() {
        return Result.ok(ragService.listEmbeddingProviders());
    }

    private String getTenantId() {
        return TenantContext.get();
    }
}
