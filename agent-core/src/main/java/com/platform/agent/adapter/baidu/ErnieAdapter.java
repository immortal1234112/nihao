package com.platform.agent.adapter.baidu;

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
 * 百度文心一言 Ernie 大模型适配器。
 * 通过 OAuth 获取 access_token，然后调用文心一言 API。
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   baidu:
 *     api-key: xxxxxxxxxxxxxxxx
 *     secret-key: xxxxxxxxxxxxxxxx
 *     model: ernie-4.0-8k
 *     max-tokens: 2048
 *     temperature: 0.7
 */
@Slf4j
@Component
public class ErnieAdapter implements LLMAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${baidu.api-key:}")
    private String apiKey;

    @Value("${baidu.secret-key:}")
    private String secretKey;

    @Value("${baidu.model:ernie-4.0-8k}")
    private String model;

    @Value("${baidu.max-tokens:2048}")
    private int maxTokens;

    @Value("${baidu.temperature:0.7}")
    private double temperature;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private volatile String cachedAccessToken;
    private volatile long tokenExpireTime;

    public ErnieAdapter(ModelConfigService configService) {
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
        return "baidu";
    }

    @Override
    public String chat(String systemPrompt, List<ChatMessage> history, String userMessage, Map<String, Object> options) {
        try {
            String effectiveApiKey = configService.getApiKey(provider(), apiKey);
            String effectiveSecretKey = configService.getApiSecret(provider(), secretKey);
            String effectiveModel = configService.getModel(provider(), model);
            String accessToken = getAccessToken(effectiveApiKey, effectiveSecretKey);

            // 构建文心一言的消息体
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
                    "messages", messages,
                    "temperature", temp,
                    "max_output_tokens", maxTok
            );

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url("https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/" + effectiveModel + "?access_token=" + accessToken)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            log.debug("Ernie call: model={}, prompt={}...", effectiveModel,
                    userMessage.substring(0, Math.min(30, userMessage.length())));

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Ernie API error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Ernie API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }

        } catch (IOException e) {
            log.error("Ernie call failed: {}", e.getMessage());
            throw new RuntimeException("Ernie 调用失败: " + e.getMessage());
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return apiKey != null && !apiKey.isEmpty() && secretKey != null && !secretKey.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) {
        try {
            Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
            // 文心一言返回 result 字段
            String result = (String) map.get("result");
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to parse Ernie response: {}", e.getMessage());
        }
        return respBody;
    }

    /**
     * 获取 access_token，带缓存（token 有效期约 30 天，提前 1 天刷新）。
     */
    private String getAccessToken(String apiKey, String secretKey) throws IOException {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpireTime) {
            return cachedAccessToken;
        }

        synchronized (this) {
            if (cachedAccessToken != null && now < tokenExpireTime) {
                return cachedAccessToken;
            }

            String url = "https://aip.baidubce.com/oauth/2.0/token"
                    + "?grant_type=client_credentials"
                    + "&client_id=" + apiKey
                    + "&client_secret=" + secretKey;

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Ernie OAuth error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Ernie OAuth error " + response.code() + ": " + respBody);
                }

                Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
                cachedAccessToken = (String) map.get("access_token");
                // expires_in 单位为秒，缓存提前 1 天过期
                int expiresIn = ((Number) map.getOrDefault("expires_in", 2592000)).intValue();
                tokenExpireTime = now + (expiresIn - 86400) * 1000L;

                log.info("Ernie access_token refreshed, expires at {}", tokenExpireTime);
                return cachedAccessToken;
            }
        }
    }
}
