package com.platform.agent.adapter.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.adapter.LLMAdapter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 大模型适配器。
 * DeepSeek API 兼容 OpenAI 格式，endpoint: https://api.deepseek.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   deepseek:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://api.deepseek.com
 *     model: deepseek-chat
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Slf4j
@Component
public class DeepSeekAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    @Value("${deepseek.max-tokens:4096}")
    private int maxTokens;

    @Value("${deepseek.temperature:0.7}")
    private double temperature;

    public DeepSeekAdapter() {
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
        return "deepseek";
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Map<String, Object> options) {
        try {
            // 构建 OpenAI 兼容的消息体
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            double temp = options != null && options.containsKey("temperature")
                    ? ((Number) options.get("temperature")).doubleValue()
                    : temperature;
            int maxTok = options != null && options.containsKey("maxTokens")
                    ? ((Number) options.get("maxTokens")).intValue()
                    : maxTokens;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", messages,
                    "temperature", temp,
                    "max_tokens", maxTok
            );

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("DeepSeek call: model={}, prompt={}...", model,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("DeepSeek API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("DeepSeek API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("DeepSeek call failed: {}", e.getMessage());
            throw new RuntimeException("DeepSeek 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        return apiKey != null && !apiKey.isEmpty() && !"your-deepseek-api-key".equals(apiKey);
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
            log.warn("Failed to parse DeepSeek response: {}", e.getMessage());
        }
        return respBody;
    }
}
