package com.platform.video.adapter.agnes;

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
 * Agnes Video V2.0 视频生成适配器（异步任务 API）。
 * 创建任务: POST https://apihub.agnes-ai.com/v1/videos
 * 查询结果: GET  https://apihub.agnes-ai.com/agnesapi?video_id=<VIDEO_ID>（推荐）
 * 模型: agnes-video-v2.0（当前 $0/秒）
 *
 * 要点（文档）:
 *   - num_frames 必须 ≤ 441 且遵循 8n+1 规则（本适配器按 duration×fps 自动折算）
 *   - 分辨率 480p/720p/1080p 三档，宽高比 16:9/9:16/1:1/4:3/3:4，超规格自动标准化
 *   - 完成后的视频 URL 位于 metadata.url
 *
 * 配置示例（管理后台模型配置页或 Nacos）:
 *   agnes-video:
 *     api-key: YOUR_API_KEY
 *     model: agnes-video-v2.0
 */
@Slf4j
@Component
public class AgnesVideoAdapter implements VideoGenerationAdapter {

    private static final String API_BASE = "https://apihub.agnes-ai.com";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${agnes-video.api-key:${agnes.api-key:}}")
    private String apiKey;

    @Value("${agnes-video.model:agnes-video-v2.0}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public AgnesVideoAdapter(ModelConfigService configService) {
        this.configService = configService;
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
                .maxDuration(18)
                .supportedResolutions(List.of("1920x1080", "1280x720"))
                .supportedFps(List.of(24, 30))
                .maxBatchSize(1)
                .build();
    }

    @Override
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        try {
            String effectiveApiKey = configService.getApiKey("agnes-video", apiKey);
            String effectiveModel = configService.getModel("agnes-video", model);
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            int fps = request.getFps() > 0 ? request.getFps() : 24;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", request.getPrompt());
            putResolution(body, request.getResolution());
            putFrames(body, duration, fps);
            if (request.getNegativePrompt() != null && !request.getNegativePrompt().isBlank()) {
                body.put("negative_prompt", request.getNegativePrompt());
            }

            String taskId = submitTask(effectiveApiKey, body);
            log.info("Agnes Video text-to-video submitted: taskId={}, duration={}s, fps={}", taskId, duration, fps);
            return pollTask(effectiveApiKey, taskId, duration, fps);

        } catch (Exception e) {
            log.error("Agnes Video text-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Agnes Video 视频生成失败: " + e.getMessage());
        }
    }

    @Override
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        try {
            String effectiveApiKey = configService.getApiKey("agnes-video", apiKey);
            String effectiveModel = configService.getModel("agnes-video", model);
            int duration = request.getDuration() > 0 ? request.getDuration() : 5;
            int fps = request.getFps() > 0 ? request.getFps() : 24;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", request.getPrompt() != null ? request.getPrompt() : "");
            if (request.getSourceImageUrl() != null && !request.getSourceImageUrl().isBlank()) {
                body.put("image", request.getSourceImageUrl());
            }
            putResolution(body, request.getResolution());
            putFrames(body, duration, fps);

            String taskId = submitTask(effectiveApiKey, body);
            log.info("Agnes Video image-to-video submitted: taskId={}, duration={}s, fps={}", taskId, duration, fps);
            return pollTask(effectiveApiKey, taskId, duration, fps);

        } catch (Exception e) {
            log.error("Agnes Video image-to-video failed: {}", e.getMessage());
            throw new RuntimeException("Agnes Video 图生视频失败: " + e.getMessage());
        }
    }

    @Override
    public VideoTaskStatus queryStatus(String externalTaskId) {
        try {
            String effectiveApiKey = configService.getApiKey("agnes-video", apiKey);
            Map<String, Object> resp = queryTask(effectiveApiKey, externalTaskId);
            String status = (String) resp.get("status");
            int progress = resp.get("progress") instanceof Number n ? n.intValue() : 0;

            VideoTaskStatus.State state = switch (status == null ? "" : status) {
                case "completed" -> VideoTaskStatus.State.DONE;
                case "failed" -> VideoTaskStatus.State.FAILED;
                case "in_progress" -> VideoTaskStatus.State.PROCESSING;
                default -> VideoTaskStatus.State.PENDING;
            };
            return VideoTaskStatus.builder()
                    .state(state)
                    .progress(state == VideoTaskStatus.State.DONE ? 100 : progress)
                    .build();
        } catch (Exception e) {
            log.error("Agnes Video queryStatus failed: {}", e.getMessage());
            return VideoTaskStatus.builder()
                    .state(VideoTaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("agnes-video")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-agnes-api-key".equals(apiKey);
    }

    // ========== 私有方法 ==========

    @SuppressWarnings("unchecked")
    private String submitTask(String apiKey, Map<String, Object> body) throws IOException {
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(API_BASE + "/v1/videos")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Agnes Video API error " + response.code() + ": " + respBody);
            }
            Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
            String videoId = (String) resp.get("video_id");
            if (videoId == null) videoId = (String) resp.get("task_id");
            if (videoId == null) {
                throw new IOException("Agnes Video response missing video_id/task_id: " + respBody);
            }
            return videoId;
        }
    }

    @SuppressWarnings("unchecked")
    private VideoGenerationResult pollTask(String apiKey, String videoId, int duration, int fps) throws Exception {
        int maxRetries = 90;  // 最长轮询 3 分钟
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(2000);
            Map<String, Object> resp = queryTask(apiKey, videoId);
            String status = (String) resp.get("status");

            if ("completed".equals(status)) {
                Map<String, Object> metadata = (Map<String, Object>) resp.get("metadata");
                String videoUrl = metadata != null ? (String) metadata.get("url") : null;
                log.info("Agnes Video task done: videoId={}, url={}", videoId, videoUrl);
                return VideoGenerationResult.builder()
                        .videoUrl(videoUrl)
                        .format("mp4")
                        .duration(duration)
                        .fps(fps)
                        .build();
            } else if ("failed".equals(status)) {
                Map<String, Object> error = (Map<String, Object>) resp.get("error");
                String msg = error != null ? String.valueOf(error.get("message")) : "unknown error";
                throw new RuntimeException("Agnes Video task failed: " + msg);
            }
            // queued / in_progress -> continue polling
        }
        throw new RuntimeException("Agnes Video task timeout after " + (maxRetries * 2) + "s");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queryTask(String apiKey, String videoId) throws IOException {
        Request request = new Request.Builder()
                .url(API_BASE + "/agnesapi?video_id=" + videoId)
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Agnes Video query error " + response.code() + ": " + respBody);
            }
            return objectMapper.readValue(respBody, Map.class);
        }
    }

    /** 分辨率 → width/height（1080p/720p 直接透传，其他交给 Agnes 标准化） */
    private void putResolution(Map<String, Object> body, String resolution) {
        int width = 1152, height = 768;
        if (resolution != null && !resolution.isBlank()) {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                try {
                    width = Integer.parseInt(parts[0].trim());
                    height = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        body.put("width", width);
        body.put("height", height);
    }

    /** duration×fps → num_frames（必须 8n+1 且 ≤441） */
    private void putFrames(Map<String, Object> body, int duration, int fps) {
        int frames = duration * fps;
        frames = ((frames - 1) / 8) * 8 + 1;  // 对齐 8n+1
        if (frames > 441) frames = 441;
        body.put("num_frames", frames);
        body.put("frame_rate", fps);
    }
}
