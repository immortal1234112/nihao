package com.platform.video.adapter.luma;

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
 * Luma Dream Machine 视频生成适配器。
 * 通过 LumaLabs API 调用: https://api.lumalabs.ai
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   luma:
 *     api-key: luma_xxxxxxxxxxxxxxxx
 *     base-url: https://api.lumalabs.ai
 *     model: dream-machine-v1
 */
@Slf4j
@Component
public class LumaAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${luma.api-key:}")
    private String apiKey;

    @Value("${luma.base-url:https://api.lumalabs.ai}")
    private String baseUrl;

    @Value("${luma.model:dream-machine-v1}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public LumaAdapter() {
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
                .maxDuration(5)
                .supportedResolutions(List.of("1360x752", "1024x576"))
                .supportedFps(List.of(24))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("model", model);
            body.put("aspect_ratio", "16:9");
            body.put("duration", duration);

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/generations")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Luma text-to-video task submitted: taskId={}", taskId);

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Luma text-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Luma 视频生成失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;

            Map<String, Object> keyframe0 = new LinkedHashMap<>();
            keyframe0.put("type", "image");
            keyframe0.put("url", request.getSourceImageUrl());

            Map<String, Object> keyframes = new LinkedHashMap<>();
            keyframes.put("frame0", keyframe0);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            body.put("model", model);
            body.put("keyframes", keyframes);

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/generations")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Luma image-to-video task submitted: taskId={}", taskId);

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Luma image-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Luma 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String url = baseUrl + "/v1/generations/" + externalTaskId;
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                String status = (String) resp.get("status");

                VideoTaskStatus.State state = mapStatus(status);

                return VideoTaskStatus.builder()
                        .state(state)
                        .progress(state == VideoTaskStatus.State.DONE ? 100 : 50)
                        .build();
            }
        } catch (Exception e) {
            log.error("Luma queryStatus failed: {}", e.getMessage());
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        return apiKey != null && !apiKey.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private String submitTask(Request httpRequest) throws IOException {
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Luma API error " + response.code() + ": " + body);
            }
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            return (String) resp.get("id");
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String taskId, int duration, int fps) throws Exception {
        String url = baseUrl + "/v1/generations/" + taskId;
        int maxRetries = 120;

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(2000);

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                String status = (String) resp.get("status");

                if ("completed".equals(status)) {
                    Map<String, Object> assets = (Map<String, Object>) resp.get("assets");
                    String videoUrl = assets != null ? (String) assets.get("video") : null;

                    log.info("Luma task done: taskId={}, videoUrl={}", taskId, videoUrl);
                    return VideoGenerationResult.builder()
                            .videoUrl(videoUrl)
                            .format("mp4")
                            .duration(duration)
                            .fps(fps)
                            .build();
                } else if ("failed".equals(status)) {
                    String error = (String) resp.get("error");
                    throw new RuntimeException("Luma task failed: " + (error != null ? error : "unknown error"));
                }
                // queued / processing -> continue polling
            }
        }

        throw new RuntimeException("Luma task timeout after " + (maxRetries * 2) + "s");
    }

    private VideoTaskStatus.State mapStatus(String status) {
        if (status == null) {
            return VideoTaskStatus.State.PENDING;
        }
        return switch (status) {
            case "completed" -> VideoTaskStatus.State.DONE;
            case "failed" -> VideoTaskStatus.State.FAILED;
            case "queued", "processing" -> VideoTaskStatus.State.PROCESSING;
            default -> VideoTaskStatus.State.PENDING;
        };
    }
}
