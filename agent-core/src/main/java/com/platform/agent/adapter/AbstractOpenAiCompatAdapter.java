package com.platform.agent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容格式（/v1/chat/completions）大模型适配器抽象基类。
 * <p>
 * 消息体构建、HTTP 调用、响应解析、健康检查等公共逻辑收敛于此，
 * 子类仅需声明各自的 @Value 配置字段并实现抽象 getter，
 * 按需覆写差异点（chatCompletionsPath / authHeader / placeholderApiKey /
 * supportsTemperatureParams）。Claude（system 顶层）与 Gemini
 * （contents+systemInstruction）请求格式不同，不继承本类。
 */
@Slf4j
public abstract class AbstractOpenAiCompatAdapter implements LLMAdapter {

    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final ModelConfigService configService;

    @Value("${platform.require-api-key:true}")
    protected boolean requireApiKey;

    protected AbstractOpenAiCompatAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 子类配置（@Value 字段 getter） ====================

    /** 配置文件里的 api-key（可能为空，由 ModelConfigService 覆盖） */
    protected abstract String apiKey();

    /** api-secret（仅 spark 等需要；默认无） */
    protected String apiSecret() {
        return null;
    }

    /** base-url（不含 /v1/chat/completions 后缀） */
    protected abstract String baseUrl();

    /** 默认模型名 */
    protected abstract String model();

    /** 默认 max_tokens */
    protected abstract int maxTokens();

    /** 默认 temperature */
    protected abstract double temperature();

    // ==================== 可覆写差异点 ====================

    /** chat/completions 路径（zhipu/qwen 的 base-url 已含 /v1 前缀，覆写为空路径前段） */
    protected String chatCompletionsPath() {
        return "/v1/chat/completions";
    }

    /** 鉴权头（spark 为 Bearer {apiKey}:{apiSecret}，覆写实现） */
    protected String authHeader(String effectiveApiKey, String effectiveApiSecret) {
        return "Bearer " + effectiveApiKey;
    }

    /** 占位 key（"your-xxx-api-key"）视为未配置；默认无 */
    protected String placeholderApiKey() {
        return null;
    }

    /** 是否在请求体附加 temperature/max_tokens（spark 不支持，返回 false） */
    protected boolean supportsTemperatureParams() {
        return true;
    }

    // ==================== LLMAdapter 实现 ====================

    @Override
    public String modelName() {
        return model();
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey());
            String effectiveApiSecret = configService.getApiSecret(provider(), apiSecret());
            String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl());
            String effectiveModel = configService.getModel(provider(), model());

            // 构建 OpenAI 兼容的消息体
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            history.forEach(h -> messages.add(Map.of("role", h.role(), "content", h.content())));
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("messages", messages);
            if (supportsTemperatureParams()) {
                body.put("temperature", resolveTemperature(options));
                body.put("max_tokens", resolveMaxTokens(options));
            }

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + chatCompletionsPath())
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", authHeader(effectiveApiKey, effectiveApiSecret))
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("{} call: model={}, prompt={}...", provider(), effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("{} API error {}: {}", provider(), response.code(), respBody);
                    throw new RuntimeException(provider() + " API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("{} call failed: {}", provider(), e.getMessage());
            throw new RuntimeException(provider() + " 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        String key = apiKey();
        if (key == null || key.isEmpty()) return false;
        String placeholder = placeholderApiKey();
        if (placeholder != null && placeholder.equals(key)) return false;
        return true;
    }

    // ==================== 内部方法 ====================

    private double resolveTemperature(Map<String, Object> options) {
        if (options != null && options.containsKey("temperature")) {
            return ((Number) options.get("temperature")).doubleValue();
        }
        return temperature();
    }

    private int resolveMaxTokens(Map<String, Object> options) {
        if (options != null && options.containsKey("maxTokens")) {
            return ((Number) options.get("maxTokens")).intValue();
        }
        return maxTokens();
    }

    /** 标准 OpenAI 兼容响应解析：choices[0].message.content */
    @SuppressWarnings("unchecked")
    protected String extractContent(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                return (String) message.get("content");
            }
        } catch (Exception e) {
            log.warn("Failed to parse {} response: {}", provider(), e.getMessage());
        }
        return respBody;
    }
}
