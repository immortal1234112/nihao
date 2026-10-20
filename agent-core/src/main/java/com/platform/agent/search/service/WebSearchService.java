package com.platform.agent.search.service;

import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.search.adapter.WebSearchAdapter;
import com.platform.agent.search.adapter.WebSearchException;
import com.platform.agent.search.dto.WebFetchResult;
import com.platform.agent.search.dto.WebSearchItem;
import com.platform.agent.search.dto.WebSearchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索编排：选适配器 → 搜索 → 转成引用 → 拼提示词段。
 * <p>
 * 定位与 {@code RagService} 对齐——都是「增强」不是「阻塞」：
 * 失败只记 WARN 并返回空，绝不让对话因为搜索挂了而失败。
 * <p>
 * 与上一版自建 SearXNG 的关键区别：这里没有常驻容器，只有一次 HTTPS 出站调用，
 * 不占内存、不需要部署编排，挂掉的影响面仅限于「本轮回答没有网页引用」。
 */
@Slf4j
@Service
public class WebSearchService {

    private final List<WebSearchAdapter> adapters;

    /** 首选适配器 provider；空则取第一个 healthCheck 通过的 */
    @Value("${search.provider:deepseek-search}")
    private String preferredProvider;

    /** 默认返回条数 */
    @Value("${search.count:5}")
    private int defaultCount;

    /** 是否请求网页正文（关闭可显著降低延迟与响应体） */
    @Value("${search.need-content:false}")
    private boolean defaultNeedContent;

    /** 注入提示词时单条网页正文的最大字符数（防 prompt 膨胀） */
    @Value("${search.prompt-max-chars-per-item:1200}")
    private int promptMaxCharsPerItem;

    /** 注入提示词时所有网页条目的总字符上限 */
    @Value("${search.prompt-max-chars-total:4000}")
    private int promptMaxCharsTotal;

    public WebSearchService(List<WebSearchAdapter> adapters) {
        this.adapters = adapters;
    }

    /**
     * 当前是否有可用的搜索适配器（已配置 Key）。
     * 供前端「联网搜索」开关做可用性判断，避免用户打开一个必然失败的开关。
     */
    public boolean isAvailable() {
        return resolveAdapter() != null;
    }

    /**
     * 执行搜索并转换为引用列表（兼容旧调用方：失败静默返回空列表）。
     * <p>
     * 需要区分「无结果」与「失败」时请用 {@link #searchWithOutcome}。
     */
    public List<RagReference> searchAsReferences(String query, String oriQuestion, Integer topK) {
        return searchWithOutcome(query, oriQuestion, topK).getReferences();
    }

    /**
     * 执行搜索，返回带错误信息的结果。
     * <p>
     * 仍然不抛异常（联网搜索是增强不是阻塞），但把失败原因显式带出来，
     * 让上层能提示用户「Key 配错了」而不是让界面显示「0 条结果」。
     *
     * @param query       搜索词
     * @param oriQuestion 用户原始问题（帮助上游理解意图），可为空
     * @param topK        本地保留条数，null 用默认值
     */
    public WebSearchOutcome searchWithOutcome(String query, String oriQuestion, Integer topK) {
        WebSearchAdapter adapter = resolveAdapter();
        if (adapter == null) {
            log.debug("联网搜索跳过：无可用适配器（未配置 API Key）");
            return WebSearchOutcome.failed("未配置搜索服务：请在「模型配置」里填写 deepseek-search 的 API Key", 401);
        }
        if (query == null || query.isBlank()) {
            return WebSearchOutcome.ok(List.of());
        }

        WebSearchRequest request = new WebSearchRequest();
        request.setQuery(query.trim());
        request.setOriQuestion(oriQuestion);
        request.setCount(topK != null && topK > 0 ? topK : defaultCount);
        request.setNeedContent(defaultNeedContent);

        long start = System.currentTimeMillis();
        try {
            List<WebSearchItem> items = adapter.search(request);
            long cost = System.currentTimeMillis() - start;
            if (items.isEmpty()) {
                // 成功但无结果：不是错误，不计费，界面上不该报警
                log.info("联网搜索无结果: query={}, {}ms", abbreviate(query, 60), cost);
                return WebSearchOutcome.ok(List.of());
            }
            List<RagReference> refs = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                refs.add(toReference(items.get(i), i));
            }
            log.info("联网搜索完成: query={}, 命中 {} 条, {}ms", abbreviate(query, 60), refs.size(), cost);
            return WebSearchOutcome.ok(refs);
        } catch (WebSearchException e) {
            log.warn("联网搜索降级（{}）: {}", e.getStatusCode(), e.getMessage());
            return WebSearchOutcome.failed(e.getMessage(), e.getStatusCode());
        } catch (Exception e) {
            log.warn("联网搜索降级（未预期异常）: {}", e.getMessage());
            return WebSearchOutcome.failed("联网搜索异常: " + e.getMessage(), 0);
        }
    }

    /**
     * 抓取单个网页正文。失败返回 null（调用方自行降级）。
     */
    public WebFetchResult fetch(String url) {
        WebSearchAdapter adapter = resolveAdapter();
        if (adapter == null) return null;
        try {
            return adapter.fetch(url);
        } catch (WebSearchException e) {
            log.warn("网页抓取失败（{}）: url={}, {}", e.getStatusCode(), abbreviate(url, 80), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("网页抓取失败（未预期异常）: url={}, {}", abbreviate(url, 80), e.getMessage());
            return null;
        }
    }

    /**
     * 把网页引用拼成提示词段，风格与 {@code RagService.buildReferencePrompt} 保持一致，
     * 让模型把两类资料都当作「可引用的外部资料」而非两种不同的东西。
     * <p>
     * 只处理 WEB 来源；没有网页引用时返回空串（调用方直接拼接不会多出空行）。
     */
    public String buildReferencePrompt(List<RagReference> refs) {
        if (refs == null || refs.isEmpty()) return "";
        List<RagReference> webRefs = refs.stream().filter(RagReference::isWeb).toList();
        if (webRefs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[联网搜索结果]（以下为实时网页内容，请优先依据这些资料回答；")
                .append("引用时标注站点名，无法从资料中确认的信息不要编造）\n");

        int total = 0;
        int index = 0;
        for (RagReference r : webRefs) {
            String body = r.getContent() == null ? "" : r.getContent();
            if (body.length() > promptMaxCharsPerItem) {
                body = body.substring(0, promptMaxCharsPerItem) + "…";
            }
            if (total + body.length() > promptMaxCharsTotal) {
                log.debug("联网搜索提示词达到总量上限 {}，截断剩余条目", promptMaxCharsTotal);
                break;
            }
            total += body.length();
            index++;
            String site = r.getSite() == null || r.getSite().isBlank() ? r.getDocumentName() : r.getSite();
            sb.append(index).append(". [来源: ").append(site).append("] ");
            if (r.getUrl() != null && !r.getUrl().isBlank()) {
                sb.append(r.getUrl()).append("\n");
            }
            sb.append(body).append("\n");
        }
        return index == 0 ? "" : sb.toString();
    }

    // ==================== 私有 ====================

    private RagReference toReference(WebSearchItem item, int index) {
        RagReference ref = new RagReference();
        // 合成 id：网页没有分块概念，但前端与存量数据都以 chunkId 作 key
        ref.setChunkId("web-" + index);
        ref.setSourceType(RagReference.SOURCE_WEB);
        ref.setDocumentName(item.getTitle() == null || item.getTitle().isBlank()
                ? (item.getSiteName() == null ? item.getUrl() : item.getSiteName())
                : item.getTitle());
        ref.setUrl(item.getUrl());
        ref.setSite(item.getSiteName());
        // 相似度语义：RAG 是余弦相似度，网页用上游排序分归一化到 0~1（上游范围 0~3）
        ref.setSimilarity(Math.max(0, Math.min(1, item.getRerankScore() / 3.0)));
        // 正文/长摘要给模型看；前端列表用同一份文本，避免两套字段
        ref.setContent(item.bestText());
        return ref;
    }

    private WebSearchAdapter resolveAdapter() {
        if (adapters == null || adapters.isEmpty()) return null;
        if (preferredProvider != null && !preferredProvider.isBlank()) {
            for (WebSearchAdapter a : adapters) {
                if (preferredProvider.equals(a.provider()) && a.healthCheck()) {
                    return a;
                }
            }
            // 首选不可用时继续找其它可用的，而不是直接放弃
        }
        for (WebSearchAdapter a : adapters) {
            if (a.healthCheck()) return a;
        }
        return null;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
