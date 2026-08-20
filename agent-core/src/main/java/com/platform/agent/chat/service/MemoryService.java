package com.platform.agent.chat.service;

import com.platform.agent.chat.entity.UserMemory;
import com.platform.agent.chat.mapper.UserMemoryMapper;
import com.platform.agent.rag.adapter.embedding.EmbeddingAdapter;
import com.platform.agent.rag.service.VectorSimilarity;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户记忆服务（长期/短期双板块）：注入（长期全量 + 短期检索）、提取落库（异步处理器调用）、列表/删除。
 * 长期（LONG_TERM）= 用户手动配置的对话基础，无条件全量注入；短期（SHORT_TERM）= 自动提取/「记住 X」指令，检索注入。
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

    @Value("${memory.long-term-max-count:30}")
    private int longTermMaxCount;

    @Value("${memory.long-term-max-chars:2000}")
    private int longTermMaxChars;

    /**
     * 记忆注入 systemPrompt（[长期记忆] 无条件全量在前，[短期记忆] 按相关性检索在后）。
     * 长期段纯 SQL 不依赖 embedding，与短期段分开 try/catch：长期段异常不影响短期，短期段异常只丢短期。
     * 任何异常降级返回原 prompt，不影响对话。
     */
    public String enhanceSystemPrompt(String userId, String tenantId, String userMessage, String systemPrompt) {
        if (!enabled) return systemPrompt;
        StringBuilder sb = new StringBuilder();

        // 1. 长期记忆：无条件全量注入（对话基础，AI 必须遵守）；条数 + 字符数双重上限保护
        List<UserMemory> longTerm = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getDeleted, 0)
                .eq(UserMemory::getCategory, UserMemory.CATEGORY_LONG_TERM)
                .orderByDesc(UserMemory::getCreatedAt)
                .last("LIMIT " + longTermMaxCount));
        if (!longTerm.isEmpty()) {
            sb.append("[长期记忆]（以下是你对该用户的长期设定，必须作为对话基础优先遵守）\n");
            int chars = 0;
            for (UserMemory m : longTerm) {
                if (chars + m.getContent().length() > longTermMaxChars) break;   // 超字符上限截断
                sb.append("- ").append(m.getContent()).append("\n");
                chars += m.getContent().length();
            }
        }

        // 2. 短期记忆：按相关性向量检索 topK；命中与长期段精确重复的过滤掉，避免 prompt 两行重复
        Set<String> longTermContents = longTerm.stream().map(UserMemory::getContent).collect(Collectors.toSet());
        try {
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            if (adapter != null) {
                List<List<Double>> vectors = adapter.embedBatch(List.of(userMessage));
                if (!vectors.isEmpty()) {
                    List<MemoryHit> hits = searchMemories(tenantId, userId, vectors.get(0), topK).stream()
                            .filter(h -> !longTermContents.contains(h.content()))
                            .toList();
                    if (!hits.isEmpty()) {
                        sb.append("[短期记忆]（对话中自动提取的相关信息，如与当前问题相关可参考）\n");
                        for (MemoryHit h : hits) {
                            sb.append("- ").append(h.content()).append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Short-term memory injection degraded: {}", e.getMessage());
        }

        if (sb.isEmpty()) return systemPrompt;
        return (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt + "\n\n" + sb : sb.toString();
    }

    /**
     * 提取结果落库（MemoryExtractProcessor 异步调用）：归属短期板块。
     * 精确匹配 + 余弦去重均限板块内（软删记录参与去重 = 板块内防复活），失败只记日志。
     */
    public void saveExtracted(String userId, String tenantId, String conversationId, String content, String type) {
        if (content == null || content.isBlank()) return;
        try {
            // 0. 数量上限（每板块独立 ≤ maxCountPerUser，超出后只 warn 不再新增；只计活跃记忆）
            Long count = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getCategory, UserMemory.CATEGORY_SHORT_TERM)
                    .eq(UserMemory::getDeleted, 0));
            if (count != null && count >= maxCountPerUser) {
                log.warn("Memory limit reached ({}), skip: user={}", maxCountPerUser, userId);
                return;
            }
            // 1. 精确去重 —— 刻意不含 deleted 过滤：软删记录也参与去重，
            //    用户删除过的内容不会被重新提取"复活"（否则删除是假删除）；限短期板块内
            Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getCategory, UserMemory.CATEGORY_SHORT_TERM)
                    .eq(UserMemory::getContent, content));
            if (dup != null && dup > 0) {
                log.debug("Memory duplicate (exact): {}", content);
                return;
            }
            // 2. 向量化 + 相似度去重（候选集限短期板块内）
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            List<List<Double>> vectors = adapter.embedBatch(List.of(content));
            if (vectors.isEmpty()) return;
            List<Double> vec = vectors.get(0);
            List<UserMemory> existing = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getCategory, UserMemory.CATEGORY_SHORT_TERM));
            for (UserMemory m : existing) {
                List<Double> existingVec = parseEmbedding(m.getEmbeddingJson());
                if (existingVec != null && VectorSimilarity.cosine(toArray(existingVec), toArray(vec)) > dedupeSimilarity) {
                    log.debug("Memory duplicate (similarity): {}", content);
                    return;
                }
            }

            insertWithEmbedding(userId, tenantId, conversationId, content, type, UserMemory.CATEGORY_SHORT_TERM, vec);
        } catch (Exception e) {
            log.warn("Memory save failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * 用户显式"记住 X"指令：归属短期板块。跳过向量去重（含软删拦截），仅精确去重短期板块活跃记忆防重复堆积。
     * 用户显式指令优先级最高 —— 之前删除过也能重新记住（限板块内）。
     */
    public void saveForced(String userId, String tenantId, String conversationId, String content, String type) {
        if (content == null || content.isBlank()) return;
        try {
            Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getTenantId, tenantId)
                    .eq(UserMemory::getCategory, UserMemory.CATEGORY_SHORT_TERM)
                    .eq(UserMemory::getDeleted, 0)
                    .eq(UserMemory::getContent, content));
            if (dup != null && dup > 0) {
                log.debug("Memory forced duplicate (active exact): {}", content);
                return;
            }
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            if (adapter == null) {
                log.warn("Memory forced save skipped: embedding provider {} unavailable", embeddingProvider);
                return;
            }
            List<List<Double>> vectors = adapter.embedBatch(List.of(content));
            if (vectors.isEmpty()) return;
            insertWithEmbedding(userId, tenantId, conversationId, content, type, UserMemory.CATEGORY_SHORT_TERM, vectors.get(0));
            log.info("Memory saved by explicit instruction: user={}, content={}", userId, content);
        } catch (Exception e) {
            log.warn("Memory forced save failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * 手动添加记忆（记忆管理页）：归属长期板块（controller 固定传 LONG_TERM）。
     * 板块内精确去重活跃记录，重复返回 DUPLICATE；embedding 失败抛异常由调用方提示。
     */
    public String saveManual(String userId, String tenantId, String content, String type, String category) {
        Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getCategory, category)
                .eq(UserMemory::getDeleted, 0)
                .eq(UserMemory::getContent, content));
        if (dup != null && dup > 0) {
            return "DUPLICATE";
        }
        EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
        if (adapter == null) {
            throw new IllegalStateException("embedding provider unavailable: " + embeddingProvider);
        }
        List<List<Double>> vectors = adapter.embedBatch(List.of(content));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("embedding failed for content");
        }
        insertWithEmbedding(userId, tenantId, null, content, type, category, vectors.get(0));
        return "OK";
    }

    /** 编辑记忆（内容/类型变更，embedding 必须重算保证检索正确；category 不变——编辑限板块内） */
    public void updateMemory(String userId, String tenantId, String memoryId, String content, String type) {
        UserMemory m = memoryMapper.selectById(memoryId);
        if (m == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        if (!m.getUserId().equals(userId) || !m.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (m.getDeleted() != null && m.getDeleted() == 1) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND, "已删除的记忆不能编辑");
        }
        UserMemory update = new UserMemory();
        update.setId(memoryId);
        update.setContent(content);
        update.setMemoryType("PREFERENCE".equalsIgnoreCase(type) ? UserMemory.TYPE_PREFERENCE : UserMemory.TYPE_FACT);
        update.setTokenCount(content.length());
        if (!content.equals(m.getContent())) {
            EmbeddingAdapter adapter = resolveAdapter(embeddingProvider);
            if (adapter == null) {
                throw new IllegalStateException("embedding provider unavailable: " + embeddingProvider);
            }
            List<List<Double>> vectors = adapter.embedBatch(List.of(content));
            if (vectors.isEmpty()) {
                throw new IllegalStateException("embedding failed for content");
            }
            update.setEmbeddingJson(JsonUtils.toJson(vectors.get(0)));
        }
        memoryMapper.updateById(update);
        log.info("Memory updated: id={}, content={}", memoryId, content);
    }

    private void insertWithEmbedding(String userId, String tenantId, String conversationId,
                                     String content, String type, String category, List<Double> vec) {
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setTenantId(tenantId);
        memory.setContent(content);
        memory.setMemoryType("PREFERENCE".equalsIgnoreCase(type) ? UserMemory.TYPE_PREFERENCE : UserMemory.TYPE_FACT);
        memory.setCategory(category);
        memory.setSourceConversationId(conversationId);
        memory.setEmbeddingJson(JsonUtils.toJson(vec));
        memory.setTokenCount(content.length());
        memory.setDeleted(0);
        memoryMapper.insert(memory);
        log.info("Memory saved: user={}, category={}, type={}, content={}", userId, category, memory.getMemoryType(), content);
    }

    // ==================== 检索（用户级候选集 + 余弦） ====================

    private List<MemoryHit> searchMemories(String tenantId, String userId, List<Double> queryVector, int topK) {
        List<UserMemory> candidates = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getCategory, UserMemory.CATEGORY_SHORT_TERM)   // 检索只覆盖短期板块
                .eq(UserMemory::getDeleted, 0)   // 软删记忆不参与注入
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
