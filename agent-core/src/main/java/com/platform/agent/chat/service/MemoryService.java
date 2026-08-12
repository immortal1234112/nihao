package com.platform.agent.chat.service;

import com.platform.agent.chat.entity.UserMemory;
import com.platform.agent.chat.mapper.UserMemoryMapper;
import com.platform.agent.rag.adapter.embedding.EmbeddingAdapter;
import com.platform.agent.rag.service.VectorSimilarity;
import com.platform.core.util.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆服务：检索注入（对话时）、提取落库（异步处理器调用）、列表/删除。
 * 记忆按 (user_id, tenant_id) 隔离，跨会话生效。
 * 检索为独立实现（候选集 LIMIT + 应用层余弦）——不能复用 MysqlVectorStore（其按 dataset_id 过滤）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryMapper memoryMapper;
    private final List<EmbeddingAdapter> embeddingAdapters;

    @Value("${memory.enabled:true}")
    private boolean enabled;

    @Value("${memory.embedding-provider:qwen-embedding}")
    private String embeddingProvider;

    @Value("${memory.top-k:3}")
    private int topK;

    @Value("${memory.search.max-candidates:500}")
    private int maxCandidates;

    @Value("${memory.dedupe-similarity:0.88}")
    private double dedupeSimilarity;

    @Value("${memory.max-count-per-user:200}")
    private int maxCountPerUser;

    /**
     * 检索相关记忆并注入 systemPrompt（[长期记忆] 段前置）。任何异常降级返回原 prompt，不影响对话。
     */
    public String enhanceSystemPrompt(String userId, String tenantId, String userMessage, String systemPrompt) {
        if (!enabled) return systemPrompt;
        try {
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            List<List<Double>> vectors = adapter.embedBatch(List.of(userMessage));
            if (vectors.isEmpty()) return systemPrompt;
            List<MemoryHit> hits = searchMemories(tenantId, userId, vectors.get(0), topK);
            if (hits.isEmpty()) return systemPrompt;

            StringBuilder sb = new StringBuilder();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                sb.append(systemPrompt).append("\n\n");
            }
            sb.append("[长期记忆]（以下是你对该用户的已知信息，如与当前问题无关可忽略）\n");
            for (MemoryHit h : hits) {
                sb.append("- ").append(h.content()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Memory injection degraded for chat: {}", e.getMessage());
            return systemPrompt;
        }
    }

    /**
     * 提取结果落库（MemoryExtractProcessor 异步调用）：精确匹配 + 余弦去重，失败只记日志。
     */
    public void saveExtracted(String userId, String tenantId, String conversationId, String content, String type) {
        if (content == null || content.isBlank()) return;
        try {
            // 0. 数量上限（防长期膨胀，超出后只 warn 不再新增）
            Long count = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId));
            if (count != null && count >= maxCountPerUser) {
                log.warn("Memory limit reached ({}), skip: user={}", maxCountPerUser, userId);
                return;
            }
            // 1. 精确去重
            Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getContent, content));
            if (dup != null && dup > 0) {
                log.debug("Memory duplicate (exact): {}", content);
                return;
            }
            // 2. 向量化 + 相似度去重
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            List<List<Double>> vectors = adapter.embedBatch(List.of(content));
            if (vectors.isEmpty()) return;
            List<Double> vec = vectors.get(0);
            List<UserMemory> existing = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId));
            for (UserMemory m : existing) {
                List<Double> existingVec = parseEmbedding(m.getEmbeddingJson());
                if (existingVec != null && VectorSimilarity.cosine(toArray(existingVec), toArray(vec)) > dedupeSimilarity) {
                    log.debug("Memory duplicate (similarity): {}", content);
                    return;
                }
            }

            UserMemory memory = new UserMemory();
            memory.setUserId(userId);
            memory.setTenantId(tenantId);
            memory.setContent(content);
            memory.setMemoryType("PREFERENCE".equalsIgnoreCase(type) ? UserMemory.TYPE_PREFERENCE : UserMemory.TYPE_FACT);
            memory.setSourceConversationId(conversationId);
            memory.setEmbeddingJson(JsonUtils.toJson(vec));
            memory.setTokenCount(content.length());
            memoryMapper.insert(memory);
            log.info("Memory saved: user={}, type={}, content={}", userId, memory.getMemoryType(), content);
        } catch (Exception e) {
            log.warn("Memory save failed (ignored): {}", e.getMessage());
        }
    }

    // ==================== 检索（用户级候选集 + 余弦） ====================

    private List<MemoryHit> searchMemories(String tenantId, String userId, List<Double> queryVector, int topK) {
        List<UserMemory> candidates = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getUserId, userId)
                .last("LIMIT " + maxCandidates));
        List<MemoryHit> hits = new ArrayList<>();
        double[] queryArr = toArray(queryVector);
        for (UserMemory m : candidates) {
            List<Double> vec = parseEmbedding(m.getEmbeddingJson());
            if (vec == null) continue;
            hits.add(new MemoryHit(m.getContent(), VectorSimilarity.cosine(toArray(vec), queryArr)));
        }
        hits.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return hits.stream().limit(topK).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Double> parseEmbedding(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return (List<Double>) JsonUtils.getMapper().readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Memory embedding parse failed: {}", e.getMessage());
            return null;
        }
    }

    private double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private EmbeddingAdapter resolveAdapter(String provider) {
        return embeddingAdapters.stream()
                .filter(a -> a.provider().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
    }

    public record MemoryHit(String content, double similarity) {
    }
}
