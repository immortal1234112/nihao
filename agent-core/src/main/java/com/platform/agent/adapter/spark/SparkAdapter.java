package com.platform.agent.adapter.spark;

import com.platform.agent.adapter.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.adapter.LLMAdapter;
import com.platform.core.config.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 讯飞星火 Spark 大模型适配器。
 * Spark API 兼容 OpenAI 格式，endpoint: https://spark-api-open.xf-yun.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   spark:
 *     app-id: xxxxxxxxxxxxxxxx
 *     api-key: xxxxxxxxxxxxxxxx
 *     api-secret: xxxxxxxxxxxxxxxx
 *     model: 4.0Ultra
 */
@Slf4j
@Component
public class SparkAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${spark.app-id:}")
    private String appId;

    @Value("${spark.api-key:}")
    private String apiKey;

    @Value("${spark.api-secret:}")
    private String apiSecret;

    @Value("${spark.model:4.0Ultra}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private static final String BASE_URL = "https://spark-api-open.xf-yun.com";

    public SparkAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String provider() {
        return "spark";
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveApiSecret = configService.getApiSecret(provider(), apiSecret);
            String effectiveModel = configService.getModel(provider(), model);
            // 构建 OpenAI 兼容的消息体
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            history.forEach(h -> messages.add(Map.of("role", h.role(), "content", h.content())));
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = Map.of(
                    "model", effectiveModel,
                    "messages", messages
            );

            String json = objectMapper.writeValueAsString(body);

            // Spark 使用 Bearer {apiKey}:{apiSecret} 作为 Authorization
            String authHeader = effectiveApiKey + ":" + effectiveApiSecret;

            Request request = new Request.Builder()
                    .url(BASE_URL + "/v1/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + authHeader)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Spark call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Spark API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Spark API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("Spark call failed: {}", e.getMessage());
            throw new RuntimeException("Spark 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return appId != null && !appId.isEmpty()
                && apiKey != null && !apiKey.isEmpty()
                && apiSecret != null && !apiSecret.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                return (String) message.get("content");
            }
        } catch (Exception e) {
            log.warn("Failed to parse Spark response: {}", e.getMessage());
        }
        return respBody;
    }
}
