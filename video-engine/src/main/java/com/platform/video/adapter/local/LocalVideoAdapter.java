package com.platform.video.adapter.local;

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
 * 本地 CogVideoX 视频生成适配器。
 * 连接本地推理服务器 (Python FastAPI)，通过 diffusers CogVideoXPipeline 生成视频。
 *
 * 本地服务器配置:
 *   local-video.base-url: 推理服务器地址，默认 http://localhost:7860
 *   local-video.model: 模型名称，默认 cogvideox-2b
 *
 * 视频生成耗时较长（GPU 上约 30-90 秒），采用异步提交 + 轮询模式。
 */
@Slf4j
@Component
public class LocalVideoAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${local-video.base-url:http://localhost:7860}")
    private String baseUrl;

    @Value("${local-video.model:cogvideox-2b}")
    private String modelName;

    @Value("${local-video.max-duration:6}")
    private int maxDuration;

    @Value("${local-video.max-frames:49}")
    private int maxFrames;

    @Value("${local-video.poll-interval-ms:3000}")
    private long pollIntervalMs;

    @Value("${local-video.poll-max-attempts:40}")
    private int pollMaxAttempts;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public LocalVideoAdapter() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return "local-cogvideox";
    }

    @Override
    public VideoModelCapability capability() {
        return VideoModelCapability.builder()
                .maxDuration(maxDuration)
                .supportedResolutions(List.of("720x480", "1360x768"))
                .supportedFps(List.of(8))
                .maxBatchSize(1)
                .build();
    }

    @Override
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        return generate(request, false);
    }

    @Override
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        return generate(request, true);
    }

    private VideoGenerationResult generate(VideoGenerateRequest request, boolean isImageToVideo) {
        int duration = request.getDuration() > 0 ? request.getDuration() : maxDuration;
        int fps = request.getFps() > 0 ? request.getFps() : 8;
        int numFrames = Math.min(duration * fps, maxFrames);
        int width = 1360;
        int height = 768;

        if (request.getResolution() != null && request.getResolution().contains("x")) {
            try {
                String[] parts = request.getResolution().split("x");
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            // 1. Submit async video generation task
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("negative_prompt", request.getNegativePrompt() != null ? request.getNegativePrompt() : "blurry, low quality, distorted");
            body.put("num_frames", numFrames);
            body.put("fps", fps);
            body.put("guidance_scale", 6.0);
            body.put("num_inference_steps", 50);
            body.put("seed", -1);

            String jsonBody = objectMapper.writeValueAsString(body);

            Request submitRequest = new Request.Builder()
                    .url(baseUrl + "/api/v1/generate/video")
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            String taskId;
            try (Response response = httpClient.newCall(submitRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Local video server error " + response.code() + ": " + respBody);
                }
                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                taskId = (String) resp.get("task_id");
                log.info("Video task submitted: taskId={}", taskId);
            }

            // 2. Poll for completion
            for (int attempt = 0; attempt < pollMaxAttempts; attempt++) {
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Video polling interrupted");
                }

                Request pollRequest = new Request.Builder()
                        .url(baseUrl + "/api/v1/generate/video/" + taskId)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(pollRequest).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        throw new IOException("Poll error " + response.code() + ": " + respBody);
                    }

                    Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                    String status = (String) resp.get("status");

                    if ("completed".equals(status)) {
                        String videoB64 = (String) resp.get("video_base64");
                        String dataUrl = "data:video/mp4;base64," + videoB64;
                        log.info("Video generation completed: taskId={}, size={} chars", taskId, videoB64 != null ? videoB64.length() : 0);

                        return VideoGenerationResult.builder()
                                .videoUrl(dataUrl)
                                .format("mp4")
                                .width(width)
                                .height(height)
                                .duration(duration)
                                .fps(fps)
                                .build();
                    } else if ("failed".equals(status)) {
                        String error = (String) resp.get("error");
                        throw new RuntimeException("Video generation failed: " + error);
                    }
                    // else "queued" or "processing" — continue polling
                }
            }

            throw new RuntimeException("Video generation timed out after " + (pollMaxAttempts * pollIntervalMs / 1000) + "s");

        } catch (Exception e) {
            log.error("Local video generation failed: {}", e.getMessage());
            throw new RuntimeException("本地 CogVideoX 调用失败: " + e.getMessage());
        }
    }

    @Override
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            Request pollRequest = new Request.Builder()
                    .url(baseUrl + "/api/v1/generate/video/" + externalTaskId)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(pollRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                String status = (String) resp.get("status");

                return switch (status) {
                    case "completed" -> VideoTaskStatus.builder()
                            .state(VideoTaskStatus.State.DONE)
                            .progress(100)
                            .build();
                    case "failed" -> VideoTaskStatus.builder()
                            .state(VideoTaskStatus.State.FAILED)
                            .errorInfo((String) resp.get("error"))
                            .build();
                    case "processing" -> VideoTaskStatus.builder()
                            .state(VideoTaskStatus.State.PROCESSING)
                            .progress(resp.get("progress") instanceof Number ? ((Number) resp.get("progress")).intValue() : 50)
                            .build();
                    default -> VideoTaskStatus.builder()
                            .state(VideoTaskStatus.State.PENDING)
                            .progress(0)
                            .build();
                };
            }
        } catch (Exception e) {
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        try {
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/api/v1/health")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                    return Boolean.TRUE.equals(resp.get("video_model_loaded")) || "ok".equals(resp.get("status"));
                }
                return false;
            }
        } catch (Exception e) {
            log.debug("Local video server health check failed: {}", e.getMessage());
            return false;
        }
    }
}
