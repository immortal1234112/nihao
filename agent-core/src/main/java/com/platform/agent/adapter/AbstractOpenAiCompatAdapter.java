package com.platform.agent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
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

            String json = objectMapper.writeValueAsString(
                    buildBody(systemPrompt, history, userMessage, options, effectiveModel, false));
            Request request = buildRequest(effectiveBaseUrl + chatCompletionsPath(), json,
                    effectiveApiKey, effectiveApiSecret);

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

    /**
     * 真流式对话（OpenAI 兼容 SSE）：{@code stream=true} + 逐行读 {@code data:} 帧。
     * <p>
     * 覆盖 8/10 个厂商；Claude（Messages API）与 Gemini（generateContent）请求格式不同，
     * 走 {@link LLMAdapter#chatStream} 的 default 兜底（整段作为单个 delta）。
     * <p>
     * 关键：<b>不设整调用 timeout</b>——OkHttp 的 readTimeout 是「每次读」的空闲超时，
     * 保留它可避免长回答被误杀；总时长由调用方的墙钟上限（chat.stream.max-duration-ms）控制。
     */
    @Override
    public void chatStream(String systemPrompt, List<ChatMessage> history, String userMessage,
                           Map<String, Object> options, StreamHandler handler) {
        String effectiveApiKey = configService.getApiKey(provider(), apiKey());
        String effectiveApiSecret = configService.getApiSecret(provider(), apiSecret());
        String effectiveBaseUrl = configService.getBaseUrl(provider(), baseUrl());
        String effectiveModel = configService.getModel(provider(), model());

        try {
            String json = objectMapper.writeValueAsString(
                    buildBody(systemPrompt, history, userMessage, options, effectiveModel, true));
            Request request = buildRequest(effectiveBaseUrl + chatCompletionsPath(), json,
                    effectiveApiKey, effectiveApiSecret);

            log.debug("{} stream call: model={}", provider(), effectiveModel);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    log.error("{} API error {}: {}", provider(), response.code(), respBody);
                    throw new RuntimeException(provider() + " API error " + response.code() + ": " + respBody);
                }
                if (response.body() == null) {
                    throw new RuntimeException(provider() + " 流式响应为空");
                }
                if (!isEventStream(response)) {
                    // 兜底①：中转网关忽略 stream=true，直接回了整套 JSON
                    log.info("{} ignored stream=true, falling back to whole-body response", provider());
                    emitWholeBody(response.body().string(), handler);
                    return;
                }
                readSseStream(response, handler);
            }
        } catch (IOException e) {
            log.error("{} stream call failed: {}", provider(), e.getMessage());
            throw new RuntimeException(provider() + " 流式调用失败: " + e.getMessage(), e);
        }
    }

    // ==================== 请求构建（同步/流式共用） ====================

    /**
     * 构建 OpenAI 兼容请求体。
     *
     * @param stream 附加 {@code "stream": true}（部分厂商对流式请求的参数校验与同步不同）
     */
    protected Map<String, Object> buildBody(String systemPrompt, List<ChatMessage> history, String userMessage,
                                            Map<String, Object> options, String effectiveModel, boolean stream) {
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
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    protected Request buildRequest(String url, String json, String apiKey, String apiSecret) {
        return new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", authHeader(apiKey, apiSecret))
                .header("Content-Type", "application/json")
                .build();
    }

    // ==================== 流式解析 ====================

    private boolean isEventStream(Response response) {
        String contentType = response.header("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("text/event-stream");
    }

    /** 逐行解析 SSE 帧；单帧解析失败只跳过，绝不中断整条流 */
    private void readSseStream(Response response, StreamHandler handler) throws IOException {
        BufferedSource source = response.body().source();
        StringBuilder rawFallback = new StringBuilder();
        boolean sawDataFrame = false;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (handler.isCancelled()) {
                log.info("{} stream cancelled by caller, stop reading", provider());
                return;
            }
            if (line.isEmpty() || line.charAt(0) == ':') {
                continue;   // 事件分隔符 / 心跳注释帧（如 ": keep-alive"）
            }
            if (!line.startsWith("data:")) {
                rawFallback.append(line);   // 非 SSE 行：留作兜底②
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            sawDataFrame = true;
            if ("[DONE]".equals(payload)) {
                break;
            }
            String delta = extractDelta(payload);
            if (delta != null && !delta.isEmpty()) {
                handler.onDelta(delta);
            }
        }
        // 兜底②：头部声明是 SSE 但一个 data 帧都没有（网关带错 Content-Type）→ 按整段 JSON 再解析
        if (!sawDataFrame && rawFallback.length() > 0) {
            log.info("{} sent event-stream header but no data frame, falling back", provider());
            emitWholeBody(rawFallback.toString(), handler);
        }
    }

    /** 把整段响应体按非流式 JSON 解析，作为单个 delta 发出 */
    private void emitWholeBody(String body, StreamHandler handler) {
        String content = extractContent(body);
        if (content != null && !content.isEmpty()) {
            handler.onDelta(content);
        }
    }

    /**
     * 取一个 SSE 帧的增量文本：{@code choices[0].delta.content}。
     * 容忍首帧 role-only（delta 无 content）、空 data、非法 JSON —— 均返回 null 交由调用方跳过。
     */
    protected String extractDelta(String payload) {
        try {
            Map<String, Object> map = objectMapper.readValue(payload, Map.class);
            List<Map<String, Object>> choices = asList(map.get("choices"));
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<String, Object> delta = asMap(choices.get(0).get("delta"));
            if (delta == null) {
                return null;
            }
            Object content = delta.get("content");
            return content == null ? null : String.valueOf(content);
        } catch (Exception e) {
            log.debug("{} stream chunk parse skipped: {}", provider(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
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
