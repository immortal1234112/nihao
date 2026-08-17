package com.platform.image.service;

import com.platform.engine.facade.AbstractGenerationFacade;
import com.platform.feign.StorageFeignClient;
import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import com.platform.mq.message.TaskResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片生成门面 —— 编排模型选择、API 调用、后处理、结果上报。
 * 回执发送与 Storage 上传辅助见 {@link AbstractGenerationFacade}。
 */
@Service
@Slf4j
public class ImageGenerationFacade extends AbstractGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final PostProcessor postProcessor;

    public ImageGenerationFacade(ModelRegistry modelRegistry, PostProcessor postProcessor,
                                 RabbitTemplate rabbitTemplate, StorageFeignClient storageFeignClient) {
        super(rabbitTemplate, storageFeignClient);
        this.modelRegistry = modelRegistry;
        this.postProcessor = postProcessor;
    }

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
                TaskResultMessage.FileInfo fi = uploadResult(taskId, result, userId, tenantId);
                if (fi != null) fileInfos.add(fi);
            }

            // 6. 发送成功结果
            long duration = System.currentTimeMillis() - start;
            reportDone(taskId, "IMAGE", fileInfos, duration, stepOrder);
            log.info("Task {} step {} completed: {} results in {}ms", taskId, stepOrder, fileInfos.size(), duration);

        } catch (Exception e) {
            reportFailed(taskId, "IMAGE", e.getMessage(), System.currentTimeMillis() - start, stepOrder);
        }
    }

    private TaskResultMessage.FileInfo uploadResult(String taskId, GenerationResult result,
                                                    String userId, String tenantId) {
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

            // 通过 Storage Feign 上传（消费线程无 ThreadLocal，身份必须显式传参；带 taskId 供归档反查）
            var uploadResult = storageFeignClient.upload(
                    createMultipartFile(bytes, "generated.png", "image/png"), userId, tenantId, taskId);
            if (uploadResult != null && uploadResult.getData() != null) {
                String fileId = uploadResult.getData().fileId();
                String url = uploadResult.getData().url();

                // 生成缩略图（不带 taskId：缩略图是派生物，不参与归档反查，避免一任务归档出两个资产）
                String thumbnailUrl = null;
                String thumbBase64 = postProcessor.generateThumbnail(result);
                if (thumbBase64 != null) {
                    byte[] thumbBytes = Base64.getDecoder().decode(thumbBase64);
                    var thumbUpload = storageFeignClient.upload(
                            createMultipartFile(thumbBytes, "thumbnail.png", "image/png"), userId, tenantId, null);
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

    @SuppressWarnings("unchecked")
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
                .referenceImages((List<String>) params.get("referenceImages"))
                .build();
    }
}
