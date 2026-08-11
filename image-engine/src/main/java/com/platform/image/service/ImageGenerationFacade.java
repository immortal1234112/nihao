package com.platform.image.service;

import com.platform.feign.StorageFeignClient;
import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;

/**
 * 图片生成门面 —— 编排模型选择、API 调用、后处理、结果上报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final PostProcessor postProcessor;
    private final RabbitTemplate rabbitTemplate;
    private final StorageFeignClient storageFeignClient;

    /**
     * 执行图片生成并上报结果。
     */
    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            // 1. 构建请求
            GenerateRequest request = buildRequest(params);

            // 2. 选择模型
            ImageGenerationAdapter adapter = modelRegistry.select(request);
            log.info("Selected model: {} for task: {}", adapter.modelName(), taskId);

            // 3. 调用模型生成
            List<GenerationResult> results = adapter.generate(request);

            // 4. 后处理
            results = postProcessor.process(results);

            // 5. 上传结果到 Storage-Service + 收集 FileInfo
            List<TaskResultMessage.FileInfo> fileInfos = new ArrayList<>();
            for (GenerationResult result : results) {
                TaskResultMessage.FileInfo fi = uploadResult(result, userId, tenantId);
                if (fi != null) fileInfos.add(fi);
            }

            // 6. 发送成功结果
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "IMAGE", "DONE", fileInfos, null, duration
            );
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
            log.info("Task {} completed: {} results in {}ms", taskId, fileInfos.size(), duration);

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage());
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "IMAGE", "FAILED", null, e.getMessage(), duration
            );
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", message);
        }
    }

    private TaskResultMessage.FileInfo uploadResult(GenerationResult result, String userId, String tenantId) {
        try {
            if (result.getBase64Data() != null && !result.getBase64Data().isEmpty()) {
                byte[] bytes = Base64.getDecoder().decode(result.getBase64Data());
                // 通过 Storage Feign 上传
                var uploadResult = storageFeignClient.upload(createMultipartFile(bytes, "png"));
                if (uploadResult != null && uploadResult.getData() != null) {
                    String fileId = uploadResult.getData().fileId();
                    String url = uploadResult.getData().url();

                    // 生成缩略图
                    String thumbnailUrl = null;
                    String thumbBase64 = postProcessor.generateThumbnail(result);
                    if (thumbBase64 != null) {
                        byte[] thumbBytes = Base64.getDecoder().decode(thumbBase64);
                        var thumbUpload = storageFeignClient.upload(createMultipartFile(thumbBytes, "png"));
                        if (thumbUpload != null && thumbUpload.getData() != null) {
                            thumbnailUrl = thumbUpload.getData().url();
                        }
                    }

                    return new TaskResultMessage.FileInfo(fileId, url, thumbnailUrl);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to upload result: {}", e.getMessage());
        }
        return new TaskResultMessage.FileInfo(null, result.getUrl(), null);
    }

    private GenerateRequest buildRequest(Map<String, Object> params) {
        return GenerateRequest.builder()
                .prompt((String) params.get("prompt"))
                .modelProvider((String) params.get("modelProvider"))
                .size((String) params.getOrDefault("size", "1024x1024"))
                .aspectRatio((String) params.get("aspectRatio"))
                .count(params.containsKey("count") ? ((Number) params.get("count")).intValue() : 1)
                .style((String) params.get("style"))
                .format((String) params.getOrDefault("format", "png"))
                .quality((String) params.getOrDefault("quality", "standard"))
                .negativePrompt((String) params.get("negativePrompt"))
                .build();
    }

    private MultipartFile createMultipartFile(byte[] bytes, String format) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return "generated." + format; }
            @Override public String getContentType() { return "image/" + format; }
            @Override public boolean isEmpty() { return bytes.length == 0; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes; }
            @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
            @Override public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
                java.nio.file.Files.write(dest.toPath(), bytes);
            }
        };
    }
}
