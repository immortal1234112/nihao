package com.platform.agent.search.adapter.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.search.adapter.WebSearchAdapter;
import com.platform.agent.search.adapter.WebSearchException;
import com.platform.agent.search.dto.WebFetchResult;
import com.platform.agent.search.dto.WebSearchItem;
import com.platform.agent.search.dto.WebSearchRequest;
import com.platform.core.config.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 官方联网搜索适配器：Search（/v1/tools/search）+ Fetch（/v1/tools/fetch）。
 * <p>
 * 与项目里其他适配器一致，API Key / baseUrl 优先取 model_config 表
 * （provider = {@code deepseek-search}），留空回退 application.yml 的默认值。
 * <p>
 * 两个接口都是「按成功请求计费」：Search 的 results 为空不计费，Fetch 没拿到正文不计费。
 * 因此「无结果」是正常返回（空列表），不是异常——不要为它重试，重试也不会产生计费结果，
 * 只是白白多打几次上游。
 */
@Slf4j
@Component
public class DeepSeekSearchAdapter implements WebSearchAdapter {

    /** model_config 里本适配器的 provider 名 */
    public static final String PROVIDER = "deepseek-search";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${search.deepseek.base-url:https://api.deepseek.com}")
    private String defaultBaseUrl;

    @Value("${search.deepseek.api-key:}")
    private String defaultApiKey;

    /** 单次请求最多尝试次数（含首次）；408/429/5xx 才重试 */
    @Value("${search.deepseek.max-attempts:3}")
    private int maxAttempts;

    /** 重试退避基数（毫秒），按 2^n 递增 */
    @Value("${search.deepseek.retry-backoff-ms:400}")
    private long retryBackoffMs;

    public DeepSeekSearchAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        // 读超时给到 30s：need_content=true 时上游要现抓网页正文，10s 不够
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean healthCheck() {
        String key = resolveApiKey();
        return key != null && !key.isBlank();
    }

    // ==================== Search ====================

    @Override
    public List<WebSearchItem> search(WebSearchRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery().trim();
        if (query.isEmpty()) {
            throw new WebSearchException("query 不能为空", 400, "invalid_request_error");
        }
        if (query.codePointCount(0, query.length()) > 128) {
            throw new WebSearchException("query 超过 128 字符", 400, "invalid_request_error");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("count", request.effectiveCount());
        body.put("need_content", request.effectiveNeedContent());
        if (request.getOriQuestion() != null && !request.getOriQuestion().isBlank()) {
            body.put("ori_question", request.getOriQuestion().trim());
        }

        JsonNode root = post("/v1/tools/search", body, "Search");
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<WebSearchItem> items = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            items.add(toItem(node));
        }
        return items;
    }

    private WebSearchItem toItem(JsonNode node) {
        WebSearchItem item = new WebSearchItem();
        item.setRerankScore(node.path("rerank_score").asDouble(0));
        item.setTitle(text(node, "title"));
        item.setUrl(text(node, "url"));
        item.setSnippet(text(node, "snippet"));
        item.setSummary(text(node, "summary"));
        item.setContent(text(node, "content"));
        item.setSiteName(nullableText(node, "site_name"));
        item.setSiteIcon(nullableText(node, "site_icon"));
        item.setDate(nullableText(node, "date"));
        item.setAuthority(node.path("authority").asInt(-1));
        return item;
    }

    // ==================== Fetch ====================

    @Override
    public WebFetchResult fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new WebSearchException("url 不能为空", 400, "invalid_request_error");
        }
        Map<String, Object> body = Map.of("url", url.trim());
        JsonNode root = post("/v1/tools/fetch", body, "Fetch");

        WebFetchResult result = new WebFetchResult();
        result.setTitle(text(root, "title"));
        result.setContent(text(root, "content"));
        result.setUrl(url.trim());
        return result;
    }

    // ==================== HTTP ====================

    /**
     * 发 POST 并按重试策略重试。返回已解析的 JSON 根节点。
     */
    private JsonNode post(String path, Map<String, Object> body, String apiName) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new WebSearchException("未配置 " + PROVIDER + " 的 API Key", 401, "unauthorized");
        }
        String baseUrl = trimTrailingSlash(resolveBaseUrl());

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new WebSearchException(apiName + " 请求体序列化失败: " + e.getMessage(), e);
        }

        int attempts = Math.max(1, maxAttempts);
        WebSearchException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Request request = new Request.Builder()
                    .url(baseUrl + path)
                    .post(RequestBody.create(json, JSON))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    return objectMapper.readTree(respBody);
                }
                WebSearchException ex = toException(response.code(), respBody, apiName);
                // 只有明确可重试的状态码才继续，其余 4xx 立即抛出
                if (!ex.retryable() || attempt == attempts) {
                    throw ex;
                }
                last = ex;
                log.warn("{} 第 {}/{} 次失败（{}），{}ms 后重试",
                        apiName, attempt, attempts, response.code(), backoff(attempt));
            } catch (IOException e) {
                // 网络层异常（连接超时、读超时、DNS）按可重试处理
                last = new WebSearchException(apiName + " 网络异常: " + e.getMessage(), e);
                if (attempt == attempts) {
                    throw last;
                }
                log.warn("{} 第 {}/{} 次网络异常（{}），{}ms 后重试",
                        apiName, attempt, attempts, e.getMessage(), backoff(attempt));
            }

            try {
                Thread.sleep(backoff(attempt));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new WebSearchException(apiName + " 重试被中断", ie);
            }
        }
        throw last != null ? last : new WebSearchException(apiName + " 调用失败", 0, null);
    }

    /**
     * 把上游错误响应转成异常。
     * <p>
     * 上游用 OpenAI 兼容错误格式：{"error":{"message":..,"type":..,"param":null,"code":..}}。
     * 但网关层故障时可能返回 HTML 或空体，此时不能假定 JSON 结构存在。
     */
    private WebSearchException toException(int status, String respBody, String apiName) {
        String message = "HTTP " + status;
        String code = null;
        try {
            if (respBody != null && !respBody.isBlank()) {
                JsonNode root = objectMapper.readTree(respBody);
                JsonNode error = root.path("error");
                if (error.isObject()) {
                    String m = error.path("message").asText("");
                    if (!m.isBlank()) message = m;
                    String c = error.path("code").asText("");
                    if (!c.isBlank()) code = c;
                } else if (respBody.length() < 200) {
                    // 非标准错误体：截断保留原文便于排查，但不把整页 HTML 塞进日志
                    message = respBody.trim();
                }
            }
        } catch (Exception ignored) {
            // 解析失败就用 HTTP 状态码兜底
        }
        return new WebSearchException(apiName + " 失败: " + message, status, code);
    }

    private long backoff(int attempt) {
        return retryBackoffMs * (1L << Math.min(attempt - 1, 4));
    }

    // ==================== 配置解析 ====================

    private String resolveApiKey() {
        return configService.getApiKey(PROVIDER, defaultApiKey);
    }

    private String resolveBaseUrl() {
        return configService.getBaseUrl(PROVIDER, defaultBaseUrl);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isBlank()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? "" : v.asText("");
    }

    /** 上游明确可能为 null 的字段：保留 null 语义，不转成空串 */
    private static String nullableText(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
