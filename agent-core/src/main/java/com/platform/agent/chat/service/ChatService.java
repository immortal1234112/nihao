package com.platform.agent.chat.service;

import com.platform.agent.adapter.ChatMessage;
import com.platform.agent.adapter.LLMAdapter;
import com.platform.agent.chat.entity.ChatConversation;
import com.platform.agent.chat.mapper.ChatConversationMapper;
import com.platform.agent.chat.mapper.ChatMessageMapper;
import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.service.RagService;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮对话编排服务：
 * 会话解析/新建 → 持久化 user 消息 → 加载历史 → 记忆注入 → RAG 增强 → 截断 → 调模型
 * → 持久化 assistant 消息（含引用）→ 异步触发记忆提取。
 * 历史截断策略：token 粗估 ceil(len×ratio)，预算内新→旧贪心，保底最近 2 条，丢弃悬空 assistant 开头（Claude 要求首条为 user）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final List<LLMAdapter> llmAdapters;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final RagService ragService;
    private final MemoryService memoryService;
    private final MemoryExtractProcessor extractProcessor;

    @Value("${chat.max-history-tokens:4096}")
    private int maxHistoryTokens;

    @Value("${chat.token-ratio:0.9}")
    private double tokenRatio;

    public Map<String, Object> chat(Map<String, Object> request, String userId, String tenantId) {
        String provider = (String) request.getOrDefault("provider", "deepseek");
        String systemPrompt = (String) request.get("systemPrompt");
        String message = (String) request.get("message");
        String datasetId = (String) request.get("datasetId");
        String conversationId = (String) request.get("conversationId");
        Number topKNum = (Number) request.get("topK");
        if (message == null || message.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "message 不能为空");
        }
        LLMAdapter adapter = resolveAdapter(provider);

        // 1. 会话解析/新建（缺省自动创建，title = 首条消息前 30 字）
        ChatConversation conversation = resolveConversation(conversationId, userId, tenantId,
                provider, datasetId, systemPrompt, message);

        // 2. 持久化 user 消息
        com.platform.agent.chat.entity.ChatMessage userMsg = new com.platform.agent.chat.entity.ChatMessage();
        userMsg.setConversationId(conversation.getId());
        userMsg.setUserId(userId);
        userMsg.setTenantId(tenantId);
        userMsg.setRole(com.platform.agent.chat.entity.ChatMessage.ROLE_USER);
        userMsg.setContent(message);
        userMsg.setTokenCount(estimateTokens(message));
        messageMapper.insert(userMsg);

        // 3. 加载历史（升序），去掉刚插入的最后一条 user（当前消息单独传给 adapter）
        List<com.platform.agent.chat.entity.ChatMessage> all = messageMapper.selectList(
                new LambdaQueryWrapper<com.platform.agent.chat.entity.ChatMessage>()
                        .eq(com.platform.agent.chat.entity.ChatMessage::getConversationId, conversation.getId())
                        .orderByAsc(com.platform.agent.chat.entity.ChatMessage::getCreatedAt));
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < all.size() - 1; i++) {
            com.platform.agent.chat.entity.ChatMessage m = all.get(i);
            history.add(new ChatMessage(m.getRole(), m.getContent()));
        }

        // 4. 记忆注入（失败降级不影响对话），顺序：[长期记忆] 在前 [参考资料] 在后
        String effectiveSystemPrompt = memoryService.enhanceSystemPrompt(userId, tenantId, message, systemPrompt);

        // 5. RAG 知识库增强（可选）
        List<RagReference> references = List.of();
        if (datasetId != null && !datasetId.isBlank()) {
            try {
                RagSearchRequest searchRequest = new RagSearchRequest();
                searchRequest.setDatasetId(datasetId);
                searchRequest.setQuery(message);
                searchRequest.setTopK(topKNum != null ? topKNum.intValue() : 3);
                RagSearchResult searchResult = ragService.search(userId, tenantId, searchRequest);
                references = searchResult.getChunks();
                effectiveSystemPrompt = (effectiveSystemPrompt == null ? "" : effectiveSystemPrompt)
                        + ragService.buildReferencePrompt(references);
            } catch (Exception e) {
                log.warn("RAG retrieval degraded for chat: {}", e.getMessage());
            }
        }

        // 6. 历史截断（预算含当前 user 消息）
        history = truncateHistory(history, message);

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) request.get("options");

        // 7. 调模型
        long start = System.currentTimeMillis();
        String response = adapter.chat(effectiveSystemPrompt, history, message, options);
        long duration = System.currentTimeMillis() - start;

        // 8. 持久化 assistant 消息（含 RAG 引用 JSON）
        com.platform.agent.chat.entity.ChatMessage assistantMsg = new com.platform.agent.chat.entity.ChatMessage();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setUserId(userId);
        assistantMsg.setTenantId(tenantId);
        assistantMsg.setRole(com.platform.agent.chat.entity.ChatMessage.ROLE_ASSISTANT);
        assistantMsg.setContent(response);
        assistantMsg.setReferencesJson(JsonUtils.toJson(references));
        assistantMsg.setTokenCount(estimateTokens(response));
        messageMapper.insert(assistantMsg);

        // 9. 更新会话计数（updatedAt 显式刷新：strictUpdateFill 不覆盖非 null 字段）
        conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 2);
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversationMapper.updateById(conversation);

        // 10. 异步记忆提取（显式传参，@Async 线程无 ThreadLocal；history 已截断，取尾部多轮作提取上下文）
        try {
            extractProcessor.extractAsync(userId, tenantId, provider, message, response, conversation.getId(), history);
        } catch (Exception e) {
            log.warn("Memory extract trigger failed (ignored): {}", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversation.getId());
        result.put("title", conversation.getTitle());
        result.put("messageId", assistantMsg.getId());
        result.put("model", adapter.modelName());
        result.put("provider", adapter.provider());
        result.put("content", response);
        result.put("durationMs", duration);
        result.put("references", references);
        return result;
    }

    // ==================== 私有 ====================

    private LLMAdapter resolveAdapter(String provider) {
        return llmAdapters.stream()
                .filter(a -> a.provider().equalsIgnoreCase(provider) && a.healthCheck())
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_UNAVAILABLE, "没有可用的 " + provider + " 模型"));
    }

    private ChatConversation resolveConversation(String conversationId, String userId, String tenantId,
                                                 String provider, String datasetId, String systemPrompt,
                                                 String firstMessage) {
        if (conversationId != null && !conversationId.isBlank()) {
            ChatConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "会话不存在: " + conversationId);
            }
            if (!conversation.getUserId().equals(userId) || !conversation.getTenantId().equals(tenantId)) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
            return conversation;
        }
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTenantId(tenantId);
        conversation.setTitle(firstMessage.length() > 30 ? firstMessage.substring(0, 30) : firstMessage);
        conversation.setProvider(provider);
        conversation.setDatasetId(datasetId);
        conversation.setSystemPrompt(systemPrompt);
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    private List<ChatMessage> truncateHistory(List<ChatMessage> history, String currentMessage) {
        if (history.isEmpty()) return history;
        int budget = maxHistoryTokens - estimateTokens(currentMessage);
        if (budget <= 0) return List.of();
        long total = history.stream()
                .mapToLong(h -> estimateTokens(h.content()))
                .sum();
        List<ChatMessage> result = new ArrayList<>(history);
        // 新→旧贪心：超预算时丢最旧（保底最近 2 条）
        while (result.size() > 2 && total > budget) {
            total -= estimateTokens(result.get(0).content());
            result.remove(0);
        }
        // 丢弃悬空 assistant 开头（Claude 要求首条为 user）
        while (!result.isEmpty() && ChatMessage.ROLE_ASSISTANT.equals(result.get(0).role())) {
            total -= estimateTokens(result.get(0).content());
            result.remove(0);
        }
        if (result.isEmpty() && total > budget) {
            return List.of();
        }
        return result;
    }

    /** 字符→token 粗估：ceil(len × ratio)（0.9 ≈ 中英混合经验值） */
    private int estimateTokens(String text) {
        return (int) Math.ceil((text == null ? 0 : text.length()) * tokenRatio);
    }
}
