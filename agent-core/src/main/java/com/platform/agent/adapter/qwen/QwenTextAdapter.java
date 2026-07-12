package com.platform.agent.adapter.qwen;

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
 * 通义千问 大模型适配器。
 * 通过阿里云 DashScope API 调用: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   qwen:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *     model: qwen-turbo
 *     max-tokens: 4096
 *     temperature: 0.7
 *
 * 可用模型: qwen-turbo, qwen-plus, qwen-max, qwen-max-longcontext
 */
@Slf4j
@Component
public class QwenTextAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${qwen.api-key:}")
    private String apiKey;

    @Value("${qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${qwen.model:qwen-turbo}")
    private String model;

    @Value("${qwen.max-tokens:4096}")
    private int maxTokens;

    @Value("${qwen.temperature:0.7}")
    private double temperature;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public QwenTextAdapter(ModelConfigService configService) {
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
        return "qwen";
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl);
            String effectiveModel = configService.getModel(provider(), model);
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
                    "model", effectiveModel,
                    "messages", messages,
                    "temperature", temp,
                    "max_tokens", maxTok
            );

            String json = objectMapper.writeValueAsString(body);

            // 千问 DashScope 使用 OpenAI 兼容模式
            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Qwen call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Qwen API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Qwen API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("Qwen call failed: {}", e.getMessage());
            throw new RuntimeException("通义千问调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-qwen-api-key".equals(apiKey);
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
            log.warn("Failed to parse Qwen response: {}", e.getMessage());
        }
        return respBody;
    }
}
