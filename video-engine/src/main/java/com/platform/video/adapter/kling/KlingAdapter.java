package com.platform.video.adapter.kling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import com.platform.video.model.VideoModelCapability;
import com.platform.video.model.VideoTaskStatus;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 快手可灵 Kling 视频生成适配器。
 * 通过 KlingAI API 调用: https://api.klingai.com
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   kling:
 *     api-key: xxxxxxxxxxxxxxxx
 *     api-secret: xxxxxxxxxxxxxxxx
 *     base-url: https://api.klingai.com
 *     model: kling-v1
 */
@Slf4j
@Component
public class KlingAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${kling.api-key:}")
    private String apiKey;

    @Value("${kling.api-secret:}")
    private String apiSecret;

    @Value("${kling.base-url:https://api.klingai.com}")
    private String baseUrl;

    @Value("${kling.model:kling-v1}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private volatile String cachedAccessToken;
    private volatile long tokenExpireTime;

    public KlingAdapter() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public VideoModelCapability capability() {
        return VideoModelCapability.builder()
                .maxDuration(10)
                .supportedResolutions(List.of("1920x1080", "1280x720"))
                .supportedFps(List.of(24, 30))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            String accessToken = getAccessToken();
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", request.getPrompt());
            body.put("duration", duration);
            body.put("aspect_ratio", "16:9");

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/videos/text2video")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Kling text-to-video task submitted: taskId={}", taskId);

            return pollTask(baseUrl + "/v1/videos/text2video/" + taskId, duration, 24);

        } catch (Exception e) {
            log.error("Kling text-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Kling 视频生成失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            String accessToken = getAccessToken();
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("image", request.getSourceImageUrl());
            body.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            body.put("duration", duration);

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/videos/image2video")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Kling image-to-video task submitted: taskId={}", taskId);

            return pollTask(baseUrl + "/v1/videos/image2video/" + taskId, duration, 24);

        } catch (Exception e) {
            log.error("Kling image-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Kling 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String accessToken = getAccessToken();

            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/videos/text2video/" + externalTaskId)
                    .get()
                    .header("Authorization", "Bearer " + accessToken)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                String status = data != null ? (String) data.get("task_status") : null;

                VideoTaskStatus.State state = mapStatus(status);

                return VideoTaskStatus.builder()
                        .state(state)
                        .progress(state == VideoTaskStatus.State.DONE ? 100 : 50)
                        .build();
            }
        } catch (Exception e) {
            log.error("Kling queryStatus failed: {}", e.getMessage());
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        return apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty();
    }

    // ---- Token 获取与缓存（参照 Baidu OAuth 模式） ----

    /**
     * 获取 access_token，带缓存。
     */
    @SuppressWarnings("unchecked")
    private String getAccessToken() throws IOException {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpireTime) {
            return cachedAccessToken;
        }

        synchronized (this) {
            if (cachedAccessToken != null && now < tokenExpireTime) {
                return cachedAccessToken;
            }

            Map<String, Object> tokenBody = new LinkedHashMap<>();
            tokenBody.put("access_key", apiKey);
            tokenBody.put("secret_key", apiSecret);

            String json = objectMapper.writeValueAsString(tokenBody);

            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/token")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Kling OAuth error {}: {}", response.code(), respBody);
                    throw new RuntimeException("Kling token获取失败: " + response.code() + " " + respBody);
                }

                Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
                Map<String, Object> data = (Map<String, Object>) map.get("data");
                cachedAccessToken = data != null ? (String) data.get("access_token") : (String) map.get("access_token");

                // 默认 1 小时过期，提前 5 分钟刷新
                int expiresIn = 3600;
                if (data != null && data.get("expires_in") != null) {
                    expiresIn = ((Number) data.get("expires_in")).intValue();
                } else if (map.get("expires_in") != null) {
                    expiresIn = ((Number) map.get("expires_in")).intValue();
                }
                tokenExpireTime = now + (expiresIn - 300) * 1000L;

                log.info("Kling access_token refreshed, expires at {}", tokenExpireTime);
                return cachedAccessToken;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String submitTask(Request httpRequest) throws IOException {
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Kling API error " + response.code() + ": " + body);
            }
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            return data != null ? (String) data.get("task_id") : (String) resp.get("task_id");
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String url, int duration, int fps) throws Exception {
        String accessToken = getAccessToken();
        int maxRetries = 120;

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(2000);

            // 每次轮询刷新 token（防止长时间轮询 token 过期）
            if (i > 0 && i % 30 == 0) {
                accessToken = getAccessToken();
            }

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + accessToken)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                String status = data != null ? (String) data.get("task_status") : null;

                if ("succeed".equals(status)) {
                    Map<String, Object> taskResult = (Map<String, Object>) data.get("task_result");
                    java.util.List<Map<String, Object>> videos = taskResult != null
                            ? (java.util.List<Map<String, Object>>) taskResult.get("videos")
                            : null;
                    String videoUrl = videos != null && !videos.isEmpty()
                            ? (String) videos.get(0).get("url")
                            : null;

                    log.info("Kling task done: videoUrl={}", videoUrl);
                    return VideoGenerationResult.builder()
                            .videoUrl(videoUrl)
                            .format("mp4")
                            .duration(duration)
                            .fps(fps)
                            .build();
                } else if ("failed".equals(status)) {
                    String error = data != null ? (String) data.get("task_status_msg") : "unknown error";
                    throw new RuntimeException("Kling task failed: " + error);
                }
                // submitted / processing -> continue polling
            }
        }

        throw new RuntimeException("Kling task timeout after " + (maxRetries * 2) + "s");
    }

    private VideoTaskStatus.State mapStatus(String status) {
        if (status == null) {
            return VideoTaskStatus.State.PENDING;
        }
        return switch (status) {
            case "succeed" -> VideoTaskStatus.State.DONE;
            case "failed" -> VideoTaskStatus.State.FAILED;
            case "submitted", "processing" -> VideoTaskStatus.State.PROCESSING;
            default -> VideoTaskStatus.State.PENDING;
        };
    }
}
