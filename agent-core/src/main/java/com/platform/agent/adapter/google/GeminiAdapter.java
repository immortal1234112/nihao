package com.platform.agent.adapter.google;

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
 * Google Gemini 大模型适配器。
 * 使用 Gemini generateContent API，endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   google:
 *     api-key: AIzaSyxxxxxxxxxxxxxxxx
 *     base-url: https://generativelanguage.googleapis.com
 *     model: gemini-2.0-flash
 *     max-tokens: 8192
 *     temperature: 0.7
 */
@Slf4j
@Component
public class GeminiAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${google.api-key:}")
    private String apiKey;

    @Value("${google.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${google.model:gemini-2.0-flash}")
    private String model;

    @Value("${google.max-tokens:8192}")
    private int maxTokens;

    @Value("${google.temperature:0.7}")
    private double temperature;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public GeminiAdapter(ModelConfigService configService) {
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
        return "google";
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl);
            String effectiveModel = configService.getModel(provider(), model);
            double temp = options != null && options.containsKey("temperature")
                    ? ((Number) options.get("temperature")).doubleValue()
                    : temperature;
            int maxTok = options != null && options.containsKey("maxTokens")
                    ? ((Number) options.get("maxTokens")).intValue()
                    : maxTokens;

            // 构建 Gemini generateContent 请求体
            Map<String, Object> userPart = Map.of("text", userMessage);
            Map<String, Object> userContent = Map.of(
                    "role", "user",
                    "parts", List.of(userPart)
            );

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("contents", List.of(userContent));

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, Object> systemPart = Map.of("text", systemPrompt);
                Map<String, Object> systemInstruction = Map.of("parts", List.of(systemPart));
                body.put("systemInstruction", systemInstruction);
            }

            Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
            generationConfig.put("temperature", temp);
            generationConfig.put("maxOutputTokens", maxTok);
            body.put("generationConfig", generationConfig);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/v1beta/models/" + effectiveModel + ":generateContent?key=" + effectiveApiKey)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Gemini call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Gemini API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Gemini API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("Gemini call failed: {}", e.getMessage());
            throw new RuntimeException("Gemini 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-google-api-key".equals(apiKey);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            // Gemini 返回 candidates[0].content.parts[0].text
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) map.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response: {}", e.getMessage());
        }
        return respBody;
    }
}
