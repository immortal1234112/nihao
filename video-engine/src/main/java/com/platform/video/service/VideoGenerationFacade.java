package com.platform.video.service;

import com.platform.feign.StorageFeignClient;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskResultMessage;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final VideoPostProcessor postProcessor;
    private final RabbitTemplate rabbitTemplate;
    private final StorageFeignClient storageFeignClient;

    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params) {
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
                String fileUrl = uploadScript(taskId, userId, tenantId, result.getScriptContent());
                if (fileUrl != null) {
                    fileInfos.add(new TaskResultMessage.FileInfo(null, fileUrl, null));
                }
            }
            if (result.getVideoUrl() != null && !result.getVideoUrl().startsWith("deepseek-chat://")) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }
            if (fileInfos.isEmpty()) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }

            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "DONE", fileInfos, null, duration);
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
            log.info("Video task {} completed in {}ms", taskId, duration);

        } catch (Exception e) {
            log.error("Video task {} failed: {}", taskId, e.getMessage());
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "FAILED", null, e.getMessage(), duration);
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", message);
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

    private int getInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    /**
     * 将脚本文本上传到 Storage Service，返回可下载的文件 URL。
     */
    @SuppressWarnings("unchecked")
    private String uploadScript(String taskId, String userId, String tenantId, String script) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            String filename = "deepseek-script-" + taskId + ".txt";

            okhttp3.RequestBody fileBody = okhttp3.RequestBody.create(
                    script.getBytes(StandardCharsets.UTF_8),
                    MediaType.parse("text/plain; charset=UTF-8"));
            okhttp3.MultipartBody multipartBody = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", filename, fileBody)
                    .addFormDataPart("storageClass", "ASSET")
                    .build();

            Request request = new Request.Builder()
                    .url("http://localhost:8084/api/v1/files/upload")
                    .header("X-User-Id", userId != null ? userId : "system")
                    .header("X-Tenant-Id", tenantId != null ? tenantId : "system")
                    .post(multipartBody)
                    .build();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    Map<String, Object> respMap = mapper.readValue(body, Map.class);
                    Map<String, Object> data = (Map<String, Object>) respMap.get("data");
                    if (data != null && data.get("id") != null) {
                        String fileId = (String) data.get("id");
                        log.info("Script uploaded: taskId={}, fileId={}", taskId, fileId);
                        return "http://localhost:8080/api/v1/files/" + fileId + "/download";
                    }
                    log.warn("Storage upload response missing data.id: {}", body.substring(0, Math.min(200, body.length())));
                } else {
                    log.warn("Storage upload failed: HTTP {} for task {}: {}", response.code(), taskId,
                            body.substring(0, Math.min(200, body.length())));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to upload script to storage: {}", e.getMessage());
        }
        return null;
    }
}
