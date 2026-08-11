package com.platform.agent.controller;

import com.platform.agent.adapter.LLMAdapter;
import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.service.RagService;
import com.platform.core.base.Result;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 大模型对话控制器 — /api/v1/llm
 * 支持 DeepSeek、千问 等已注册的 LLMAdapter。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmController {

    private final List<LLMAdapter> llmAdapters;
    private final RagService ragService;

    /**
     * 列出所有可用的 LLM 模型。
     */
    @GetMapping("/models")
    public Result<List<Map<String, String>>> listModels() {
        List<Map<String, String>> models = llmAdapters.stream()
                .filter(LLMAdapter::healthCheck)
                .map(a -> Map.of(
                        "model", a.modelName(),
                        "provider", a.provider(),
                        "available", "true"
                ))
                .toList();
        return Result.ok(models);
    }

    /**
     * 发起对话。
     */
    @AuditLog(action = "LLM_CHAT")
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String provider = (String) request.getOrDefault("provider", "deepseek");
        String systemPrompt = (String) request.get("systemPrompt");
        String message = (String) request.get("message");
        String datasetId = (String) request.get("datasetId");
        Number topKNum = (Number) request.get("topK");

        if (message == null || message.isEmpty()) {
            return Result.fail(1001, "message 不能为空");
        }

        // 查找匹配的适配器
        LLMAdapter adapter = llmAdapters.stream()
                .filter(a -> a.provider().equalsIgnoreCase(provider) && a.healthCheck())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("没有可用的 " + provider + " 模型"));

        // RAG 知识库增强（可选）：检索命中注入 systemPrompt，失败降级不影响对话
        List<RagReference> references = List.of();
        String effectiveSystemPrompt = systemPrompt;
        if (datasetId != null && !datasetId.isBlank()) {
            try {
                RagSearchRequest searchRequest = new RagSearchRequest();
                searchRequest.setDatasetId(datasetId);
                searchRequest.setQuery(message);
                searchRequest.setTopK(topKNum != null ? topKNum.intValue() : 3);
                RagSearchResult searchResult = ragService.search(
                        UserContext.getUserId(), TenantContext.get(), searchRequest);
                references = searchResult.getChunks();
                effectiveSystemPrompt = (systemPrompt == null ? "" : systemPrompt)
                        + ragService.buildReferencePrompt(references);
            } catch (Exception e) {
                log.warn("RAG retrieval degraded for chat: {}", e.getMessage());
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) request.get("options");

        long start = System.currentTimeMillis();
        String response = adapter.chat(effectiveSystemPrompt, message, options);
        long duration = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("model", adapter.modelName());
        result.put("provider", adapter.provider());
        result.put("content", response);
        result.put("durationMs", duration);
        result.put("references", references);

        return Result.ok(result);
    }
}
