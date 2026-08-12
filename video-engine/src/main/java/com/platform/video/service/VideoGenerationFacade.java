package com.platform.video.service;

import com.platform.feign.StorageFeignClient;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskResultMessage;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }
            if (fileInfos.isEmpty()) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }

            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "DONE", fileInfos, null, duration, stepOrder);
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
            log.info("Video task {} step {} completed in {}ms", taskId, stepOrder, duration);

        } catch (Exception e) {
            log.error("Video task {} failed: {}", taskId, e.getMessage());
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "FAILED", null, e.getMessage(), duration, stepOrder);
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
     * 将脚本文本上传到 Storage Service（走带身份头的 Feign 内部接口，返回可下载 URL）。
     */
    private TaskResultMessage.FileInfo uploadScript(String taskId, String userId, String tenantId, String script) {
        try {
            String filename = "deepseek-script-" + taskId + ".txt";
            MultipartFile file = createTextMultipartFile(script, filename);
            var uploadResult = storageFeignClient.upload(file,
                    userId != null ? userId : "system",
                    tenantId != null ? tenantId : "system");
            if (uploadResult != null && uploadResult.getData() != null) {
                String fileId = uploadResult.getData().fileId();
                log.info("Script uploaded: taskId={}, fileId={}", taskId, fileId);
                return new TaskResultMessage.FileInfo(fileId, uploadResult.getData().url(), null);
            }
            log.warn("Script upload returned empty result: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("Failed to upload script to storage: {}", e.getMessage());
        }
        return null;
    }

    private MultipartFile createTextMultipartFile(String content, String filename) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return filename; }
            @Override public String getContentType() { return "text/plain; charset=UTF-8"; }
            @Override public boolean isEmpty() { return bytes.length == 0; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            @Override public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
                java.nio.file.Files.write(dest.toPath(), bytes);
            }
        };
    }
}
