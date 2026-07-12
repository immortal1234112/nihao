package com.platform.video.adapter.wan;

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
 * 通义万相 Wan2.1 视频生成适配器（免费 DashScope API）。
 *
 * 文生视频模型: wan2.1-t2v
 * 图生视频模型: wan2.1-i2v
 *
 * API 路径:
 *   提交任务 → POST https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis
 *   查询结果 → GET  https://dashscope.aliyuncs.com/api/v1/tasks/{task_id}
 *
 * 配置: wan21.api-key (回退到 qwen.api-key，同一把 DashScope Key)
 */
@Slf4j
@Component
public class WanVideoAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${wan21.api-key:${qwen.api-key:}}")
    private String apiKey;

    @Value("${wan21.t2v-model:wan2.2-12v-flash}")
    private String t2vModel;

    @Value("${wan21.i2v-model:wan2.2-12v-flash}")
    private String i2vModel;

    @Value("${wan21.size:1280x720}")
    private String defaultSize;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String SUBMIT_URL = BASE_URL + "/api/v1/services/aigc/video-generation/video-synthesis";
    private static final String TASK_URL = BASE_URL + "/api/v1/tasks/";

    public WanVideoAdapter(ModelConfigService configService) {
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
        return "wan2.1";
    }

    @Override
    public VideoModelCapability capability() {
        return VideoModelCapability.builder()
                .maxDuration(10)
                .supportedResolutions(List.of("1280x720", "720x1280", "1024x576"))
                .supportedFps(List.of(16, 24))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            String size = request.getResolution() != null ? request.getResolution() : defaultSize;

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", request.getPrompt());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", configService.getModel("wan21", t2vModel));
            body.put("input", input);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("duration", duration);
            params.put("size", size);
            body.put("parameters", params);

            String taskId = submitTask(body);
            log.info("Wan2.1 text-to-video submitted: taskId={}, prompt={}", taskId,
                    request.getPrompt().substring(0, Math.min(50, request.getPrompt().length())));

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Wan2.1 text-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Wan2.1 文生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            String size = request.getResolution() != null ? request.getResolution() : defaultSize;

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            input.put("image_url", request.getSourceImageUrl());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", configService.getModel("wan21", i2vModel));
            body.put("input", input);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("duration", duration);
            params.put("size", size);
            body.put("parameters", params);

            String taskId = submitTask(body);
            log.info("Wan2.1 image-to-video submitted: taskId={}", taskId);

            return pollTask(taskId, duration, 24);

        } catch (Exception e) {
            log.error("Wan2.1 image-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Wan2.1 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String effectiveApiKey = configService.getApiKey("wan21",
                    configService.getApiKey("qwen", apiKey));

            Request request = new Request.Builder()
                    .url(TASK_URL + externalTaskId)
                    .get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                Map<String, Object> output = (Map<String, Object>) resp.get("output");
                String status = output != null ? (String) output.get("task_status") : null;

                return VideoTaskStatus.builder()
                        .state(mapState(status))
                        .progress("SUCCEEDED".equals(status) ? 100 : 50)
                        .build();
            }
        } catch (Exception e) {
            log.error("Wan2.1 queryStatus failed: {}", e.getMessage());
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;
        if (configService.isEnabled("wan21")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-qwen-api-key".equals(apiKey);
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private String submitTask(Map<String, Object> body) throws IOException {
        String effectiveApiKey = configService.getApiKey("wan21",
                configService.getApiKey("qwen", apiKey));
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(SUBMIT_URL)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Wan2.1 API error " + response.code() + ": " + respBody);
            }
            Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
            Map<String, Object> output = (Map<String, Object>) resp.get("output");
            String taskId = output != null ? (String) output.get("task_id") : null;
            if (taskId == null) {
                throw new IOException("No task_id in response: " + respBody);
            }
            return taskId;
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String taskId, int duration, int fps) throws Exception {
        String effectiveApiKey = configService.getApiKey("wan21",
                configService.getApiKey("qwen", apiKey));
        int maxRetries = 120; // 最多等 240 秒

        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(2000);

            Request request = new Request.Builder()
                    .url(TASK_URL + taskId)
                    .get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                Map<String, Object> output = (Map<String, Object>) resp.get("output");
                String status = output != null ? (String) output.get("task_status") : null;

                if ("SUCCEEDED".equals(status)) {
                    Map<String, Object> results = (Map<String, Object>) output.get("results");
                    String videoUrl = results != null ? (String) results.get("video_url") : null;

                    log.info("Wan2.1 task done: taskId={}, videoUrl={}", taskId, videoUrl);
                    return VideoGenerationResult.builder()
                            .videoUrl(videoUrl)
                            .format("mp4")
                            .duration(duration)
                            .fps(fps)
                            .build();
                } else if ("FAILED".equals(status)) {
                    String error = output != null ? (String) output.get("message") : "unknown";
                    throw new RuntimeException("Wan2.1 task failed: " + error);
                }
                // PENDING / RUNNING → 继续轮询
                if (i % 15 == 0) {
                    log.info("Wan2.1 task {} still {}, elapsed {}s", taskId, status, i * 2);
                }
            }
        }

        throw new RuntimeException("Wan2.1 task timeout after " + (maxRetries * 2) + "s");
    }

    private VideoTaskStatus.State mapState(String status) {
        if (status == null) return VideoTaskStatus.State.PENDING;
        return switch (status) {
            case "SUCCEEDED" -> VideoTaskStatus.State.DONE;
            case "FAILED" -> VideoTaskStatus.State.FAILED;
            case "RUNNING" -> VideoTaskStatus.State.PROCESSING;
            default -> VideoTaskStatus.State.PENDING;
        };
    }
}