package com.platform.agent.adapter.zhipu;

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
 * 智谱 GLM 大模型适配器。
 * 智谱 API 兼容 OpenAI 格式，endpoint: https://open.bigmodel.cn/api/paas/v4/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   zhipu:
 *     api-key: xxxxxxxxxxxxxxxx.xxxxxxxxxxxxxxxx
 *     base-url: https://open.bigmodel.cn/api/paas/v4
 *     model: glm-4-plus
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Slf4j
@Component
public class GlmAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${zhipu.api-key:}")
    private String apiKey;

    @Value("${zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${zhipu.model:glm-4-plus}")
    private String model;

    @Value("${zhipu.max-tokens:4096}")
    private int maxTokens;

    @Value("${zhipu.temperature:0.7}")
    private double temperature;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public GlmAdapter(ModelConfigService configService) {
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
        return "zhipu";
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
                    .url(effectiveBaseUrl + "/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("GLM call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("GLM API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("GLM API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("GLM call failed: {}", e.getMessage());
            throw new RuntimeException("GLM 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-zhipu-api-key".equals(apiKey);
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
            log.warn("Failed to parse GLM response: {}", e.getMessage());
        }
        return respBody;
    }
}
