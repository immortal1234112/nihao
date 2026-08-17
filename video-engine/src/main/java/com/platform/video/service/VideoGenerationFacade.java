package com.platform.video.service;

import com.platform.engine.facade.AbstractGenerationFacade;
import com.platform.feign.StorageFeignClient;
import com.platform.mq.message.TaskResultMessage;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 视频生成门面 —— 编排模型选择、API 调用、后处理、结果上报。
 * 回执发送与 Storage 上传辅助见 {@link AbstractGenerationFacade}。
 */
@Service
@Slf4j
public class VideoGenerationFacade extends AbstractGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final VideoPostProcessor postProcessor;

    public VideoGenerationFacade(ModelRegistry modelRegistry, VideoPostProcessor postProcessor,
                                 RabbitTemplate rabbitTemplate, StorageFeignClient storageFeignClient) {
        super(rabbitTemplate, storageFeignClient);
        this.modelRegistry = modelRegistry;
        this.postProcessor = postProcessor;
    }

    /**
     * 执行视频生成并上报结果。
     *
     * @param stepOrder 对应 agent-core 的步骤序号，回执时回填以便精确对账（BATCH 多步骤）
     */
    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params, int stepOrder) {
        long start = System.currentTimeMillis();
        try {
            VideoGenerateRequest request = buildRequest(params);
            VideoGenerationAdapter adapter = modelRegistry.select(request);
            log.info("Selected video model: {} for task: {}", adapter.modelName(), taskId);

            // 文生视频 or 图生视频
            VideoGenerationResult result;
            if (request.getSourceImageUrl() != null && !request.getSourceImageUrl().isEmpty()) {
                result = adapter.imageToVideo(request);
            } else {
                result = adapter.textToVideo(request);
            }

            result = postProcessor.process(result);

            // 处理文本脚本输出（DeepSeek 等）：上传到 Storage 获得可下载 URL
            List<TaskResultMessage.FileInfo> fileInfos = new ArrayList<>();
            if (result.getScriptContent() != null && !result.getScriptContent().isEmpty()) {
                TaskResultMessage.FileInfo fi = uploadScript(taskId, userId, tenantId, result.getScriptContent());
                if (fi != null) {
                    fileInfos.add(fi);
                }
            }
            if (result.getVideoUrl() != null && !result.getVideoUrl().startsWith("deepseek-chat://")) {
                // 下载 provider 直链视频字节 → 上传 Storage（带 taskId，归档可按任务反查）
                TaskResultMessage.FileInfo fi = uploadVideo(taskId, userId, tenantId, result);
                fileInfos.add(fi);
            }
            if (fileInfos.isEmpty()) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }

            long duration = System.currentTimeMillis() - start;
            reportDone(taskId, "VIDEO", fileInfos, duration, stepOrder);

        } catch (Exception e) {
            reportFailed(taskId, "VIDEO", e.getMessage(), System.currentTimeMillis() - start, stepOrder);
        }
    }

    private VideoGenerateRequest buildRequest(Map<String, Object> params) {
        return VideoGenerateRequest.builder()
                .prompt((String) params.get("prompt"))
                .duration(getInt(params, "duration", 5))
                .fps(getInt(params, "fps", 24))
                .resolution((String) params.getOrDefault("resolution", "1080p"))
                .style((String) params.get("style"))
                .sourceImageUrl((String) params.get("sourceImageUrl"))
                .negativePrompt((String) params.get("negativePrompt"))
                .modelProvider((String) params.get("modelProvider"))
                .build();
    }

    /**
     * 将脚本文本上传到 Storage Service（走带身份头的 Feign 内部接口，返回可下载 URL）。
     */
    private TaskResultMessage.FileInfo uploadScript(String taskId, String userId, String tenantId, String script) {
        return uploadToStorage(taskId, userId, tenantId,
                script.getBytes(StandardCharsets.UTF_8),
                "deepseek-script-" + taskId + ".txt",
                "text/plain; charset=UTF-8");
    }

    /**
     * 视频结果落库：下载 provider 直链 → 上传 Storage（带 taskId 供归档反查）。
     * 失败降级回退直链 FileInfo（fileId=null，任务仍 DONE，与 fail-open 策略一致）。
     */
    private TaskResultMessage.FileInfo uploadVideo(String taskId, String userId, String tenantId,
                                                   VideoGenerationResult result) {
        try {
            byte[] bytes = downloadBytes(result.getVideoUrl());
            if (bytes == null || bytes.length == 0) {
                throw new java.io.IOException("video bytes empty");
            }
            String filename = "generated-" + taskId + ".mp4";
            TaskResultMessage.FileInfo uploaded = uploadToStorage(taskId, userId, tenantId,
                    bytes, filename, "video/mp4");
            if (uploaded == null) {
                throw new java.io.IOException("storage upload returned empty result");
            }
            // 缩略图可下载则一并落库；失败不影响主文件
            String thumbnailUrl = result.getThumbnailUrl();
            if (result.getThumbnailUrl() != null && !result.getThumbnailUrl().isBlank()) {
                try {
                    byte[] thumbBytes = downloadBytes(result.getThumbnailUrl());
                    // 缩略图不带 taskId（派生物不参与归档反查，避免重复归档）
                    TaskResultMessage.FileInfo thumb = uploadToStorage(null, userId, tenantId,
                            thumbBytes, "thumbnail-" + taskId + ".png", "image/png");
                    if (thumb != null) {
                        thumbnailUrl = thumb.url();
                    }
                } catch (Exception e) {
                    log.warn("Thumbnail upload failed for task={}: {}", taskId, e.getMessage());
                }
            }
            return new TaskResultMessage.FileInfo(uploaded.fileId(), uploaded.url(), thumbnailUrl);
        } catch (Exception e) {
            log.warn("Failed to persist video for task={}: {} (fallback to provider URL)", taskId, e.getMessage());
        }
        return new TaskResultMessage.FileInfo(null, result.getVideoUrl(), result.getThumbnailUrl());
    }

    /** 下载 provider 直链字节（视频/缩略图通用） */
    private byte[] downloadBytes(String url) throws java.io.IOException {
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
        try (okhttp3.Response response = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new java.io.IOException("download failed: HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }
}
