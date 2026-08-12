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
     *
     * @param stepOrder 对应 agent-core 的步骤序号，回执时回填以便精确对账（BATCH 多步骤）
     */
    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params, int stepOrder) {
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
                    taskId, "IMAGE", "DONE", fileInfos, null, duration, stepOrder
            );
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
            log.info("Task {} step {} completed: {} results in {}ms", taskId, stepOrder, fileInfos.size(), duration);

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage());
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "IMAGE", "FAILED", null, e.getMessage(), duration, stepOrder
            );
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", message);
        }
    }

    private TaskResultMessage.FileInfo uploadResult(GenerationResult result, String userId, String tenantId) {
        try {
            byte[] bytes;
            if (result.getBase64Data() != null && !result.getBase64Data().isEmpty()) {
                bytes = Base64.getDecoder().decode(result.getBase64Data());
            } else if (result.getUrl() != null && !result.getUrl().isBlank()) {
                // 适配器只返回 URL（如 agnes/本地直链）→ 下载字节后上传，保证结果真正落库
                bytes = downloadBytes(result.getUrl());
            } else {
                return new TaskResultMessage.FileInfo(null, null, null);
            }
            if (bytes == null || bytes.length == 0) {
                throw new IOException("result bytes empty");
            }

            // 通过 Storage Feign 上传（消费线程无 ThreadLocal，身份必须显式传参）
            var uploadResult = storageFeignClient.upload(createMultipartFile(bytes, "png"), userId, tenantId);
            if (uploadResult != null && uploadResult.getData() != null) {
                String fileId = uploadResult.getData().fileId();
                String url = uploadResult.getData().url();

                // 生成缩略图
                String thumbnailUrl = null;
                String thumbBase64 = postProcessor.generateThumbnail(result);
                if (thumbBase64 != null) {
                    byte[] thumbBytes = Base64.getDecoder().decode(thumbBase64);
                    var thumbUpload = storageFeignClient.upload(createMultipartFile(thumbBytes, "png"), userId, tenantId);
                    if (thumbUpload != null && thumbUpload.getData() != null) {
                        thumbnailUrl = thumbUpload.getData().url();
                    }
                }

                return new TaskResultMessage.FileInfo(fileId, url, thumbnailUrl);
            }
            throw new IOException("storage upload returned empty result");
        } catch (Exception e) {
            log.warn("Failed to upload result: {}", e.getMessage());
        }
        // 降级：上传失败回退 provider 直链（与系统 fail-open 策略一致）
        return new TaskResultMessage.FileInfo(null, result.getUrl(), null);
    }

    /** 从 provider 直链下载图片字节（agnes 等只返回 URL 的适配器用） */
    private byte[] downloadBytes(String url) throws IOException {
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
        try (okhttp3.Response response = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("download failed: HTTP " + response.code());
            }
            return response.body().bytes();
        }
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
