package com.platform.agent.adapter.anthropic;

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
 * Anthropic Claude 大模型适配器。
 * 使用 Anthropic Messages API，endpoint: https://api.anthropic.com/v1/messages
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   anthropic:
 *     api-key: sk-ant-xxxxxxxxxxxxxxxx
 *     base-url: https://api.anthropic.com
 *     model: claude-sonnet-4-20250514
 *     max-tokens: 4096
 *     temperature: 0.7
 *     version: 2023-06-01
 */
@Slf4j
@Component
public class ClaudeAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${anthropic.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${anthropic.max-tokens:4096}")
    private int maxTokens;

    @Value("${anthropic.temperature:0.7}")
    private double temperature;

    @Value("${anthropic.version:2023-06-01}")
    private String version;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public ClaudeAdapter(ModelConfigService configService) {
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
        return "anthropic";
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl);
            String effectiveModel = configService.getModel(provider(), model);
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "user", "content", userMessage));

            double temp = options != null && options.containsKey("temperature")
                    ? ((Number) options.get("temperature")).doubleValue()
                    : temperature;
            int maxTok = options != null && options.containsKey("maxTokens")
                    ? ((Number) options.get("maxTokens")).intValue()
                    : maxTokens;

            // Anthropic Messages API 格式
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("max_tokens", maxTok);
            body.put("temperature", temp);
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                body.put("system", systemPrompt);
            }
            body.put("messages", messages);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/v1/messages")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("x-api-key", effectiveApiKey)
                    .header("anthropic-version", version)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Anthropic call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Anthropic API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Anthropic API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("Anthropic call failed: {}", e.getMessage());
            throw new RuntimeException("Anthropic 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-anthropic-api-key".equals(apiKey);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            // Anthropic 返回 content 是一个数组，取第一个 content block 的 text
            List<Map<String, Object>> content = (List<Map<String, Object>>) map.get("content");
            if (content != null && !content.isEmpty()) {
                return (String) content.get(0).get("text");
            }
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic response: {}", e.getMessage());
        }
        return respBody;
    }
}
