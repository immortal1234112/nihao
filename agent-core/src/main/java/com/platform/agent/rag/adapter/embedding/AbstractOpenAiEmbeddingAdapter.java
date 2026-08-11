package com.platform.agent.rag.adapter.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容协议 embedding 适配器基类。
 * OpenAI 与 Qwen(DashScope compatible-mode) 的 /embeddings 接口 wire 格式完全一致，
 * 差异仅 baseUrl 拼接路径：openai → /v1/embeddings，qwen → /embeddings（baseUrl 已含 /compatible-mode/v1）。
 * API Key / baseUrl / model 优先取 model_config 表（ModelConfigService），留空回退 @Value 默认值。
 */
@Slf4j
public abstract class AbstractOpenAiEmbeddingAdapter implements EmbeddingAdapter {

    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final ModelConfigService configService;

    @Value("${platform.require-api-key:true}")
    protected boolean requireApiKey;

    protected AbstractOpenAiEmbeddingAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** embedding API 路径：OpenAI="/v1/embeddings"，Qwen="/embeddings" */
    protected abstract String embeddingPath();

    protected abstract String getConfiguredApiKey();

    protected abstract String getConfiguredBaseUrl();

    protected abstract String getConfiguredModel();

    @Override
    public String modelName() {
        return configService.getModel(provider(), getConfiguredModel());
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        String effectiveApiKey = configService.getApiKey(provider(), getConfiguredApiKey());
        String effectiveBaseUrl = configService.getBaseUrl(provider(), getConfiguredBaseUrl());
        String effectiveModel = configService.getModel(provider(), getConfiguredModel());

        try {
            Map<String, Object> body = Map.of("model", effectiveModel, "input", texts);
            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + embeddingPath())
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Embedding call: provider={}, model={}, texts={}", provider(), effectiveModel, texts.size());
            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Embedding API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Embedding API error " + response.code() + ": " + respBody);
                }
                return parseEmbeddings(respBody);
            }
        } catch (IOException e) {
            log.error("Embedding call failed: {}", e.getMessage());
            throw new RuntimeException("Embedding 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        String key = getConfiguredApiKey();
        return key != null && !key.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> parseEmbeddings(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) map.get("data");
            if (data == null || data.isEmpty()) {
                throw new RuntimeException("Embedding 响应为空");
            }
            // 按 index 排序保证与入参顺序一致
            List<Map<String, Object>> sorted = new ArrayList<>(data);
            sorted.sort(Comparator.comparingInt(m -> ((Number) m.get("index")).intValue()));
            List<List<Double>> result = new ArrayList<>(sorted.size());
            for (Map<String, Object> item : sorted) {
                result.add((List<Double>) item.get("embedding"));
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 响应解析失败: " + e.getMessage());
        }
    }
}
