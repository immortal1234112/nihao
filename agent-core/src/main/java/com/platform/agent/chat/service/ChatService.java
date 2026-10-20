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
import com.platform.agent.search.service.WebSearchOutcome;
import com.platform.agent.search.service.WebSearchService;
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
 * <p>
 * 流水线的前半段（组装上下文）抽在 {@link #prepare}，同步 {@link #chat} 与流式
 * {@code ChatStreamService} 共用——两条路径只有「怎么调模型」不同，上下文组装完全一致。
 * <p>
 * 历史截断策略：token 粗估 ceil(len×ratio)，预算内新→旧贪心，保底最近 2 条，
 * 丢弃悬空 assistant 开头（Claude 要求首条为 user）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final List<LLMAdapter> llmAdapters;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final RagService ragService;
    private final WebSearchService webSearchService;
    private final MemoryService memoryService;
    private final MemoryExtractProcessor extractProcessor;
    private final MemorySpaceService memorySpaceService;

    @Value("${chat.max-history-tokens:4096}")
    private int maxHistoryTokens;

    @Value("${chat.token-ratio:0.9}")
    private double tokenRatio;

    // ==================== 阶段事件（同步路径不发，流式路径转 SSE） ====================

    /**
     * 流水线阶段事件：流式路径用它告诉前端「上下文已组装完，模型开始产出了」，
     * 让首个 token 到达前的那几秒有明确文案而不是空转。
     */
    public record ChatPhaseEvent(String phase, String status, int count, String message) {
        public static ChatPhaseEvent start(String phase) {
            return new ChatPhaseEvent(phase, "start", 0, null);
        }

        public static ChatPhaseEvent done(String phase, int count) {
            return new ChatPhaseEvent(phase, "done", count, null);
        }

        public static ChatPhaseEvent failed(String phase, String message) {
            return new ChatPhaseEvent(phase, "failed", 0, message);
        }
    }

    /** 流水线准备结果：步骤 1~5 的全部产物，交给调用方执行「怎么调模型」 */
    public record PreparedContext(
            ChatConversation conversation,
            LLMAdapter adapter,
            String systemPrompt,
            List<ChatMessage> history,
            String userMessage,
            Map<String, Object> options,
            List<RagReference> references,
            /** 本次对话生效的记忆空间（注入与提取都用它，保证同一轮读写一致） */
            String spaceId,
            /**
             * 联网搜索失败原因；null = 没开搜索或搜索成功。
             * 需要单独带出来，否则「搜索失败」在界面上会表现成「0 条结果」，
             * 用户无法区分「真的没搜到」和「Key 配错了」。
             */
            String searchError) {
    }

    // ==================== 同步对话 ====================

    public Map<String, Object> chat(Map<String, Object> request, String userId, String tenantId) {
        PreparedContext ctx = prepare(request, userId, tenantId);

        long start = System.currentTimeMillis();
        String response = ctx.adapter().chat(ctx.systemPrompt(), ctx.history(), ctx.userMessage(), ctx.options());
        long duration = System.currentTimeMillis() - start;

        com.platform.agent.chat.entity.ChatMessage assistantMsg =
                persistAssistant(ctx.conversation(), userId, tenantId, response, ctx.references());
        afterTurn(ctx.conversation(), ctx.adapter().provider(), userId, tenantId,
                ctx.spaceId(), ctx.userMessage(), response, ctx.history());

        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", ctx.conversation().getId());
        result.put("title", ctx.conversation().getTitle());
        result.put("messageId", assistantMsg.getId());
        result.put("model", ctx.adapter().modelName());
        result.put("provider", ctx.adapter().provider());
        result.put("content", response);
        result.put("durationMs", duration);
        result.put("references", ctx.references());
        result.put("spaceId", ctx.spaceId());
        // 联网搜索失败时一并返回原因，前端与流式路径展示一致
        if (ctx.searchError() != null) {
            result.put("searchError", ctx.searchError());
        }
        return result;
    }

    // ==================== 流水线准备（同步/流式共用） ====================

    /**
     * 执行步骤 1~5：会话解析 → 存 user 消息 → 加载历史 → 记忆注入 → RAG → 截断。
     * <p>
     * RAG 是「增强」不是「阻塞」：失败时 catch 降级，不影响回答。
     */
    public PreparedContext prepare(Map<String, Object> request, String userId, String tenantId) {
        String provider = (String) request.getOrDefault("provider", "deepseek");
        String systemPrompt = (String) request.get("systemPrompt");
        String message = (String) request.get("message");
        String datasetId = (String) request.get("datasetId");
        String conversationId = (String) request.get("conversationId");
        String requestSpaceId = (String) request.get("spaceId");
        Number topKNum = (Number) request.get("topK");
        if (message == null || message.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "message 不能为空");
        }
        LLMAdapter adapter = resolveAdapter(provider);

        // 1. 会话解析/新建（缺省自动创建，title = 首条消息前 30 字）
        ChatConversation conversation = resolveConversation(conversationId, userId, tenantId,
                provider, datasetId, systemPrompt, requestSpaceId, message);

        // 1.5 解析本次生效的记忆空间：显式指定 > 会话已绑定 > 用户默认空间。
        //     新会话已在 resolveConversation 里绑定；老会话（space_id 为 NULL）回落到默认空间，
        //     行为与改造前一致（改造前记忆是全局一套，等价于"默认空间"）。
        String spaceId = memorySpaceService.resolveSpaceId(userId, tenantId,
                requestSpaceId, conversation.getSpaceId());
        if (conversation.getSpaceId() == null || conversation.getSpaceId().isBlank()) {
            // 老会话首次在新版本里使用时补绑，后续就固定了
            conversation.setSpaceId(spaceId);
            conversationMapper.updateById(conversation);
        }

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

        // 4. 记忆注入（失败降级不影响对话）—— 限定在本次生效的记忆空间内
        String effectiveSystemPrompt = memoryService.enhanceSystemPrompt(userId, tenantId, spaceId, message, systemPrompt);

        // 引用列表：落库到同一条 references_json
        List<RagReference> references = new ArrayList<>();

        // 5. RAG 知识库增强（可选；失败降级不阻塞回答，与记忆注入同一降级范式）
        if (datasetId != null && !datasetId.isBlank()) {
            try {
                RagSearchRequest searchRequest = new RagSearchRequest();
                searchRequest.setDatasetId(datasetId);
                searchRequest.setQuery(message);
                searchRequest.setTopK(topKNum != null ? topKNum.intValue() : 3);
                RagSearchResult searchResult = ragService.search(userId, tenantId, searchRequest);
                List<RagReference> ragRefs = searchResult.getChunks();
                references.addAll(ragRefs);
                effectiveSystemPrompt = (effectiveSystemPrompt == null ? "" : effectiveSystemPrompt)
                        + ragService.buildReferencePrompt(ragRefs);
            } catch (Exception e) {
                log.warn("RAG retrieval degraded for chat: {}", e.getMessage());
            }
        }

        // 6. 联网搜索增强（可选；失败降级不阻塞回答，与 RAG 同一降级范式）
        //    开关来自请求体 webSearch=true，缺省关闭——搜索按次计费，不能默认替用户花钱
        String searchError = null;
        if (Boolean.TRUE.equals(request.get("webSearch"))) {
            try {
                WebSearchOutcome outcome = webSearchService.searchWithOutcome(
                        message, message, topKNum != null ? topKNum.intValue() : null);
                if (outcome.hasError()) {
                    // 失败原因一路带到 SSE，前端据此提示；对话本身照常继续
                    searchError = outcome.getError();
                } else if (!outcome.getReferences().isEmpty()) {
                    List<RagReference> webRefs = outcome.getReferences();
                    references.addAll(webRefs);
                    effectiveSystemPrompt = (effectiveSystemPrompt == null ? "" : effectiveSystemPrompt)
                            + webSearchService.buildReferencePrompt(webRefs);
                }
            } catch (Exception e) {
                log.warn("联网搜索降级 for chat: {}", e.getMessage());
                searchError = "联网搜索异常: " + e.getMessage();
            }
        }

        // 7. 历史截断（预算含当前 user 消息）
        history = truncateHistory(history, message);

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) request.get("options");

        return new PreparedContext(conversation, adapter, effectiveSystemPrompt, history,
                message, options, references, spaceId, searchError);
    }

    // ==================== 流水线收尾（同步/流式共用） ====================

    /** 持久化 assistant 消息（含引用 JSON） */
    com.platform.agent.chat.entity.ChatMessage persistAssistant(ChatConversation conversation, String userId,
                                                                String tenantId, String content,
                                                                List<RagReference> references) {
        com.platform.agent.chat.entity.ChatMessage assistantMsg = new com.platform.agent.chat.entity.ChatMessage();
        assistantMsg.setConversationId(conversation.getId());
        assistantMsg.setUserId(userId);
        assistantMsg.setTenantId(tenantId);
        assistantMsg.setRole(com.platform.agent.chat.entity.ChatMessage.ROLE_ASSISTANT);
        assistantMsg.setContent(content);
        assistantMsg.setReferencesJson(JsonUtils.toJson(references));
        assistantMsg.setTokenCount(estimateTokens(content));
        messageMapper.insert(assistantMsg);
        return assistantMsg;
    }

    /** 更新会话计数 + 异步记忆提取 */
    void afterTurn(ChatConversation conversation, String provider, String userId, String tenantId,
                   String spaceId, String userMessage, String answer, List<ChatMessage> history) {
        // updatedAt 显式刷新：strictUpdateFill 不覆盖非 null 字段
        conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 2);
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversationMapper.updateById(conversation);

        // 异步记忆提取（显式传参，@Async 线程无 ThreadLocal；history 已截断，取尾部多轮作提取上下文）
        // spaceId 必须透传：提取结果写回本次对话绑定的空间，不能落到默认空间
        try {
            extractProcessor.extractAsync(userId, tenantId, spaceId, provider, userMessage, answer,
                    conversation.getId(), history);
        } catch (Exception e) {
            log.warn("Memory extract trigger failed (ignored): {}", e.getMessage());
        }
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
                                                 String requestSpaceId, String firstMessage) {
        if (conversationId != null && !conversationId.isBlank()) {
            ChatConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "会话不存在: " + conversationId);
            }
            if (!conversation.getUserId().equals(userId) || !conversation.getTenantId().equals(tenantId)) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
            // 空间在创建时绑定、之后不可变更：显式传了不同 spaceId 直接拒绝，避免上下文撕裂
            if (requestSpaceId != null && !requestSpaceId.isBlank()
                    && conversation.getSpaceId() != null && !conversation.getSpaceId().isBlank()
                    && !requestSpaceId.equals(conversation.getSpaceId())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "会话已绑定其他记忆空间，如需切换请新建对话");
            }
            return conversation;
        }
        // 新建会话：此时绑定空间（显式指定则校验归属，否则用默认空间）
        String spaceId = memorySpaceService.resolveSpaceId(userId, tenantId, requestSpaceId, null);
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTenantId(tenantId);
        conversation.setTitle(firstMessage.length() > 30 ? firstMessage.substring(0, 30) : firstMessage);
        conversation.setProvider(provider);
        conversation.setDatasetId(datasetId);
        conversation.setSpaceId(spaceId);
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
