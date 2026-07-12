package com.platform.image.meme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * justmeme.wtf 模板 API 客户端（带 Caffeine 本地缓存和兜底模板）。
 */
@Slf4j
@Component
public class MemeTemplateClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, List<Map<String, String>>> cache;

    @Value("${meme.template-api:https://justmeme.wtf/api/v1/templates}")
    private String templateApi;

    @Value("${meme.template-cache-ttl:600}")
    private long cacheTtlSeconds;

    public MemeTemplateClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(600))
                .maximumSize(1)
                .build();
    }

    /**
     * 获取模板列表（优先缓存，失败走兜底）。
     */
    public List<Map<String, String>> getTemplates() {
        // 1. 查缓存
        List<Map<String, String>> cached = cache.getIfPresent("templates");
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 2. 调远程 API
        try {
            String url = templateApi + "?page=1&limit=5";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    List<Map<String, String>> templates = parseTemplates(body);
                    if (!templates.isEmpty()) {
                        cache.put("templates", templates);
                        log.info("Loaded {} meme templates from justmeme.wtf", templates.size());
                        return templates;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to fetch meme templates from justmeme.wtf: {}", e.getMessage());
        }

        // 3. 兜底
        log.info("Using fallback meme templates");
        List<Map<String, String>> fallback = getFallbackTemplates();
        cache.put("templates", fallback);
        return fallback;
    }

    /**
     * 刷新模板缓存（供下次请求使用）。
     */
    public void refreshCache() {
        cache.invalidate("templates");
    }

    // ==================== 解析 ====================

    private List<Map<String, String>> parseTemplates(String body) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            // 响应格式: { "templates": [ { "id": "...", "name": "...", "lines": N } ] }
            JsonNode templates = root.has("templates") ? root.get("templates")
                    : root.has("data") ? root.get("data") : root;
            if (templates.isArray()) {
                for (JsonNode t : templates) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("id", t.has("id") ? t.get("id").asText() : "");
                    item.put("name", t.has("name") ? t.get("name").asText() : "");
                    item.put("lines", t.has("lines") ? t.get("lines").asText() : "2");
                    result.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse meme templates response: {}", e.getMessage());
        }
        return result;
    }

    // ==================== 兜底模板 ====================

    private List<Map<String, String>> getFallbackTemplates() {
        List<Map<String, String>> templates = new ArrayList<>();

        templates.add(Map.of("id", "distracted-boyfriend", "name", "Distracted Boyfriend",
                "lines", "3", "description", "男人回头看另一个女人，女友瞪着他"));
        templates.add(Map.of("id", "drake-hotline-bling", "name", "Drake Hotline Bling",
                "lines", "2", "description", "Drake 摆手拒绝 vs 手指满意"));
        templates.add(Map.of("id", "two-buttons", "name", "Two Buttons",
                "lines", "3", "description", "两个按钮，痛苦选择"));
        templates.add(Map.of("id", "change-my-mind", "name", "Change My Mind",
                "lines", "2", "description", "一个人坐在桌后，桌上放着 Change My Mind 牌子"));
        templates.add(Map.of("id", "woman-yelling-at-cat", "name", "Woman Yelling at Cat",
                "lines", "2", "description", "左边女人对右边白猫吼叫"));

        return templates;
    }
}
