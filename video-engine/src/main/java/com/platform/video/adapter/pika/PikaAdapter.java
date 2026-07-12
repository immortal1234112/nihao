package com.platform.video.adapter.pika;

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
 * Pika Labs 视频生成适配器。
 * 通过 Pika API 调用: https://api.pika.art
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   pika:
 *     api-key: sk_xxxxxxxxxxxxxxxx
 *     base-url: https://api.pika.art
 *     model: pika-2.0
 */
@Slf4j
@Component
public class PikaAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${pika.api-key:}")
    private String apiKey;

    @Value("${pika.base-url:https://api.pika.art}")
    private String baseUrl;

    @Value("${pika.model:pika-2.0}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public PikaAdapter() {
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
                .maxDuration(8)
                .supportedResolutions(List.of("1920x1080", "1280x720", "1024x576"))
                .supportedFps(List.of(24))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            String resolution = request.getResolution() != null ? request.getResolution() : "1280x720";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("resolution", resolution);
            body.put("duration", duration);
            if (request.getNegativePrompt() != null && !request.getNegativePrompt().isEmpty()) {
                body.put("negative_prompt", request.getNegativePrompt());
            }

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/videos")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Pika text-to-video task submitted: taskId={}", taskId);

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Pika text-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Pika 视频生成失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            String resolution = request.getResolution() != null ? request.getResolution() : "1280x720";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            body.put("image_url", request.getSourceImageUrl());
            body.put("resolution", resolution);
            body.put("duration", duration);

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/v1/videos")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId = submitTask(httpRequest);
            log.info("Pika image-to-video task submitted: taskId={}", taskId);

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Pika image-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Pika 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String url = baseUrl + "/v1/videos/" + externalTaskId;
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
            log.error("Pika queryStatus failed: {}", e.getMessage());
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
                throw new IOException("Pika API error " + response.code() + ": " + body);
            }
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            return (String) resp.get("id");
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String taskId, int duration, int fps) throws Exception {
        String url = baseUrl + "/v1/videos/" + taskId;
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
                    String videoUrl = (String) resp.get("video_url");

                    log.info("Pika task done: taskId={}, videoUrl={}", taskId, videoUrl);
                    return VideoGenerationResult.builder()
                            .videoUrl(videoUrl)
                            .format("mp4")
                            .duration(duration)
                            .fps(fps)
                            .build();
                } else if ("failed".equals(status)) {
                    String error = (String) resp.get("error");
                    throw new RuntimeException("Pika task failed: " + (error != null ? error : "unknown error"));
                }
                // pending / processing -> continue polling
            }
        }

        throw new RuntimeException("Pika task timeout after " + (maxRetries * 2) + "s");
    }

    private VideoTaskStatus.State mapStatus(String status) {
        if (status == null) {
            return VideoTaskStatus.State.PENDING;
        }
        return switch (status) {
            case "completed" -> VideoTaskStatus.State.DONE;
            case "failed" -> VideoTaskStatus.State.FAILED;
            case "processing" -> VideoTaskStatus.State.PROCESSING;
            default -> VideoTaskStatus.State.PENDING;
        };
    }
}
