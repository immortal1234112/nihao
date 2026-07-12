package com.platform.video.adapter.cogvideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
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
 * 智谱 CogVideoX-Flash 免费视频生成适配器。
 *
 * 模型: cogvideox-flash（完全免费，速度优先）
 * API:  https://open.bigmodel.cn/api/paas/v4/videos/generations
 *
 * 配置: cogvideo.api-key（回退到 zhipu.api-key）
 *
 * 特性:
 *   - 文生视频 / 图生视频
 *   - 最高 4K、60fps
 *   - 支持 AI 音效
 *   - 最长 10 秒
 */
@Slf4j
@Component
public class CogVideoXFlashAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${cogvideo.api-key:${zhipu.api-key:}}")
    private String apiKey;

    @Value("${cogvideo.model:cogvideox-flash}")
    private String model;

    @Value("${cogvideo.base-url:https://open.bigmodel.cn}")
    private String baseUrl;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private static final String GENERATIONS_PATH = "/api/paas/v4/videos/generations";
    private static final String ASYNC_RESULT_PATH = "/api/paas/v4/async-result";

    public CogVideoXFlashAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 接口实现 ====================

    @Override
    public String modelName() {
        return "cogvideox-flash";
    }

    @Override
    public VideoModelCapability capability() {
        return VideoModelCapability.builder()
                .maxDuration(10)
                .supportedResolutions(List.of("1280x720", "1920x1080", "3840x2160"))
                .supportedFps(List.of(30, 60))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            int fps = request.getFps() > 0 ? request.getFps() : 30;
            String size = request.getResolution() != null
                    ? request.getResolution() : "1280x720";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", configService.getModel("cogvideo", model));
            body.put("prompt", request.getPrompt());
            body.put("quality", "speed");
            body.put("with_audio", false);
            body.put("size", size);
            body.put("fps", fps);
            body.put("duration", duration);

            String taskId = submitTask(body);
            log.info("CogVideoX-Flash T2V submitted: taskId={}, prompt={}", taskId,
                    request.getPrompt().substring(0, Math.min(50, request.getPrompt().length())));

            return pollTask(taskId, duration, fps);

        } catch (Exception e) {
            log.error("CogVideoX-Flash T2V failed: {}", e.getMessage());
            throw new RuntimeException("CogVideoX 文生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            int fps = request.getFps() > 0 ? request.getFps() : 30;
            String size = request.getResolution() != null
                    ? request.getResolution() : "1280x720";

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", configService.getModel("cogvideo", model));
            body.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            body.put("image_url", request.getSourceImageUrl());
            body.put("quality", "speed");
            body.put("with_audio", false);
            body.put("size", size);
            body.put("fps", fps);
            body.put("duration", duration);

            String taskId = submitTask(body);
            log.info("CogVideoX-Flash I2V submitted: taskId={}", taskId);

            return pollTask(taskId, duration, fps);

        } catch (Exception e) {
            log.error("CogVideoX-Flash I2V failed: {}", e.getMessage());
            throw new RuntimeException("CogVideoX 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String effectiveApiKey = configService.getApiKey("cogvideo",
                    configService.getApiKey("zhipu", apiKey));

            Request request = new Request.Builder()
                    .url(baseUrl + ASYNC_RESULT_PATH + "/" + externalTaskId)
                    .get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                String status = (String) resp.get("task_status");

                return VideoTaskStatus.builder()
                        .state(mapState(status))
                        .progress("SUCCESS".equals(status) ? 100 : 50)
                        .build();
            }
        } catch (Exception e) {
            log.error("CogVideoX queryStatus failed: {}", e.getMessage());
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;
        if (configService.isEnabled("cogvideo")) return true;
        if (configService.isEnabled("zhipu")) return true;
        return apiKey != null && !apiKey.isEmpty();
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private String submitTask(Map<String, Object> body) throws IOException {
        String effectiveApiKey = configService.getApiKey("cogvideo",
                configService.getApiKey("zhipu", apiKey));
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(baseUrl + GENERATIONS_PATH)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("CogVideoX API error " + response.code() + ": " + respBody);
            }
            Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
            String taskId = (String) resp.get("id");
            if (taskId == null) {
                throw new IOException("No task id in response: " + respBody);
            }
            return taskId;
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String taskId, int duration, int fps) throws Exception {
        String effectiveApiKey = configService.getApiKey("cogvideo",
                configService.getApiKey("zhipu", apiKey));
        int maxRetries = 300; // 最多等 600 秒，免费版较慢

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(2000);

            Request request = new Request.Builder()
                    .url(baseUrl + ASYNC_RESULT_PATH + "/" + taskId)
                    .get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                String status = (String) resp.get("task_status");

                if ("SUCCESS".equals(status)) {
                    List<Map<String, Object>> videoResult =
                            (List<Map<String, Object>>) resp.get("video_result");
                    String videoUrl = videoResult != null && !videoResult.isEmpty()
                            ? (String) videoResult.get(0).get("url")
                            : null;

                    log.info("CogVideoX task done: taskId={}, videoUrl={}", taskId, videoUrl);
                    return VideoGenerationResult.builder()
                            .videoUrl(videoUrl)
                            .format("mp4")
                            .duration(duration)
                            .fps(fps)
                            .build();
                } else if ("FAIL".equals(status)) {
                    throw new RuntimeException("CogVideoX task failed");
                }
                // PROCESSING → 继续轮询
                if (i % 15 == 0) {
                    log.info("CogVideoX task {} status={}, elapsed {}s", taskId, status, i * 2);
                }
            }
        }

        throw new RuntimeException("CogVideoX task timeout after " + (maxRetries * 2) + "s");
    }

    private VideoTaskStatus.State mapState(String status) {
        if (status == null) return VideoTaskStatus.State.PENDING;
        return switch (status) {
            case "SUCCESS" -> VideoTaskStatus.State.DONE;
            case "FAIL" -> VideoTaskStatus.State.FAILED;
            case "PROCESSING" -> VideoTaskStatus.State.PROCESSING;
            default -> VideoTaskStatus.State.PENDING;
        };
    }
}
