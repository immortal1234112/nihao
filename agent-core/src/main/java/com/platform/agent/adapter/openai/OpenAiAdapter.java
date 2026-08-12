package com.platform.agent.adapter.openai;

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
 * OpenAI 大模型适配器。
 * OpenAI API 兼容格式，endpoint: https://api.openai.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   openai:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://api.openai.com
 *     model: gpt-4o
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Slf4j
@Component
public class OpenAiAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Value("${openai.max-tokens:4096}")
    private int maxTokens;

    @Value("${openai.temperature:0.7}")
    private double temperature;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public OpenAiAdapter(ModelConfigService configService) {
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
        return "openai";
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl);
            String effectiveModel = configService.getModel(provider(), model);
            // 构建 OpenAI 兼容的消息体
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            history.forEach(h -> messages.add(Map.of("role", h.role(), "content", h.content())));
            messages.add(Map.of("role", "user", "content", userMessage));

            double temp = options != null && options.containsKey("temperature")
                    ? ((Number) options.get("temperature")).doubleValue()
                    : temperature;
            int maxTok = options != null && options.containsKey("maxTokens")
                    ? ((Number) options.get("maxTokens")).intValue()
                    : maxTokens;

            Map<String, Object> body = Map.of(
                    "model", effectiveModel,
                    "messages", messages,
                    "temperature", temp,
                    "max_tokens", maxTok
            );

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/v1/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("OpenAI call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("OpenAI API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("OpenAI API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("OpenAI call failed: {}", e.getMessage());
            throw new RuntimeException("OpenAI 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-openai-api-key".equals(apiKey);
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
            log.warn("Failed to parse OpenAI response: {}", e.getMessage());
        }
        return respBody;
    }
}
