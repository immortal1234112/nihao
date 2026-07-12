package com.platform.image.adapter.baidu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import com.platform.image.model.ModelCapability;
import com.platform.image.model.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 百度文心一格 图片生成适配器。
 * 通过百度 AI 平台 API 调用: https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/text2image/{model}
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   ernie-image:
 *     api-key: xxxxxxxxxxxxxxxx
 *     secret-key: xxxxxxxxxxxxxxxx
 *     model: ernie-vilg-v2
 *     size: 1024x1024
 *
 * 可用模型: ernie-vilg-v2
 */
@Slf4j
@Component
public class ErnieImageAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${ernie-image.api-key:${baidu.api-key:}}")
    private String apiKey;

    @Value("${ernie-image.secret-key:${baidu.secret-key:}}")
    private String secretKey;

    @Value("${ernie-image.model:ernie-vilg-v2}")
    private String modelName;

    @Value("${ernie-image.size:1024x1024}")
    private String defaultSize;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private volatile String cachedAccessToken;
    private volatile long tokenExpireTime;

    public ErnieImageAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public ModelCapability capability() {
        return ModelCapability.builder()
                .supportedResolutions(List.of("1024x1024", "768x768", "512x512"))
                .supportedStyles(List.of("realistic", "anime", "watercolor", "oil-painting", "sketch"))
                .supportedFormats(List.of("png", "jpg"))
                .maxBatchSize(4)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        String size = request.getSize() != null ? request.getSize() : defaultSize;
        int count = request.getCount() > 0 ? request.getCount() : 1;
        String effectiveApiKey = configService.getApiKey("ernie-image", apiKey);
        String effectiveSecretKey = configService.getApiSecret("ernie-image", secretKey);
        String effectiveModel = configService.getModel("ernie-image", modelName);

        try {
            String accessToken = getAccessToken(effectiveApiKey, effectiveSecretKey);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("size", size);
            body.put("n", count);
            if (request.getStyle() != null) {
                body.put("style", request.getStyle());
            }

            String json = objectMapper.writeValueAsString(body);
            String url = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/text2image/"
                    + effectiveModel + "?access_token=" + accessToken;

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Ernie Image API error " + response.code() + ": " + respBody);
                }

                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);

                // Handle API error
                Object errorCode = resp.get("error_code");
                if (errorCode != null) {
                    throw new RuntimeException("Ernie Image API error: " + resp.get("error_msg"));
                }

                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                if (data != null) {
                    int w = parseWidth(size);
                    int h = parseHeight(size);
                    String fmt = request.getFormat() != null ? request.getFormat() : "png";
                    for (Map<String, Object> item : data) {
                        results.add(GenerationResult.builder()
                                .url((String) item.get("url"))
                                .format(fmt)
                                .width(w)
                                .height(h)
                                .build());
                    }
                }
            }

            log.info("Ernie Image generation done: {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Ernie Image generation failed: {}", e.getMessage());
            throw new RuntimeException("文心一格调用失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskStatus queryStatus(String externalTaskId) {
        // Ernie Image is synchronous
        return TaskStatus.builder()
                .state(TaskStatus.State.DONE)
                .progress(100)
                .build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("ernie-image")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-baidu-api-key".equals(apiKey)
                && secretKey != null && !secretKey.isEmpty() && !"your-baidu-secret-key".equals(secretKey);
    }

    /**
     * 获取百度 OAuth 2.0 access_token，带缓存。
     */
    @SuppressWarnings("unchecked")
    private synchronized String getAccessToken(String apiKey, String secretKey) throws IOException {
        long now = System.currentTimeMillis();
        // Token 缓存有效 25 天（百度 access_token 有效期 30 天）
        if (cachedAccessToken != null && now < tokenExpireTime) {
            return cachedAccessToken;
        }

        String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token"
                + "?grant_type=client_credentials"
                + "&client_id=" + apiKey
                + "&client_secret=" + secretKey;

        Request tokenRequest = new Request.Builder()
                .url(tokenUrl)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build();

        try (Response response = httpClient.newCall(tokenRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);

            Object error = resp.get("error");
            if (error != null) {
                throw new IOException("Failed to get Baidu access token: " + resp.get("error_description"));
            }

            cachedAccessToken = (String) resp.get("access_token");
            Object expiresIn = resp.get("expires_in");
            long expiresInSeconds = expiresIn instanceof Number ? ((Number) expiresIn).longValue() : 2592000;
            // 提前 1 天刷新
            tokenExpireTime = now + (expiresInSeconds - 86400) * 1000L;

            log.info("Baidu access token refreshed, expires in {}s", expiresInSeconds);
            return cachedAccessToken;
        }
    }

    private int parseWidth(String size) {
        if (size == null) return 1024;
        String[] parts = size.split("x");
        return parts.length >= 1 ? Integer.parseInt(parts[0]) : 1024;
    }

    private int parseHeight(String size) {
        if (size == null) return 1024;
        String[] parts = size.split("x");
        return parts.length >= 2 ? Integer.parseInt(parts[1]) : 1024;
    }
}
