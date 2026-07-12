package com.platform.image.adapter.qwen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import com.platform.image.model.ModelCapability;
import com.platform.image.model.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 通义万相 图片生成适配器。
 * 通过阿里云 DashScope API 调用: https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   qwen-image:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     model: wanx-v1
 *     size: 1024*1024
 *
 * 可用模型: wanx-v1（通义万相）
 */
@Slf4j
@Component
public class QwenImageAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${qwen-image.api-key:${qwen.api-key:}}")
    private String apiKey;

    @Value("${qwen-image.model:wanx-v1}")
    private String modelName;

    @Value("${qwen-image.size:1024*1024}")
    private String defaultSize;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public QwenImageAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public ModelCapability capability() {
        return ModelCapability.builder()
                .supportedResolutions(List.of("1024*1024", "720*1280", "1280*720"))
                .supportedStyles(List.of("photorealistic", "anime", "oil-painting", "watercolor", "sketch"))
                .supportedFormats(List.of("png", "jpg"))
                .maxBatchSize(4)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        String size = request.getSize() != null
            ? request.getSize().replace('x', '*') : defaultSize;
        String effectiveApiKey = configService.getApiKey("qwen-image",
            configService.getApiKey("qwen", apiKey));  // 回退到 qwen LLM 的 Key
        String effectiveModel = configService.getModel("qwen-image", modelName);

        try {
            // ---- 步骤 1: 提交异步任务 ----
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", request.getPrompt());
            if (request.getNegativePrompt() != null) {
                input.put("negative_prompt", request.getNegativePrompt());
            }

            Map<String, Object> taskBody = new LinkedHashMap<>();
            taskBody.put("model", effectiveModel);
            taskBody.put("input", input);
            Map<String, Object> parameters = new LinkedHashMap<>();
            if (size != null) parameters.put("size", size);
            parameters.put("n", request.getCount());
            taskBody.put("parameters", parameters);

            String taskJson = objectMapper.writeValueAsString(taskBody);

            Request taskRequest = new Request.Builder()
                    .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis")
                    .post(RequestBody.create(taskJson, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .build();

            String taskId;
            try (Response response = httpClient.newCall(taskRequest).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Qwen Image API error " + response.code() + ": " + body);
                }
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                Map<String, Object> output = (Map<String, Object>) resp.get("output");
                taskId = (String) output.get("task_id");
            }

            log.info("Qwen Image task submitted: taskId={}", taskId);

            // ---- 步骤 2: 轮询结果（最多等待 32 秒） ----
            String statusUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;
            int maxRetries = 40;
            for (int i = 0; i < maxRetries; i++) {
                Thread.sleep(800);

                Request statusRequest = new Request.Builder()
                        .url(statusUrl)
                        .get()
                        .header("Authorization", "Bearer " + effectiveApiKey)
                        .build();

                try (Response response = httpClient.newCall(statusRequest).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                    Map<String, Object> output = (Map<String, Object>) resp.get("output");
                    String taskStatus = (String) output.get("task_status");

                    if ("SUCCEEDED".equals(taskStatus)) {
                        List<Map<String, String>> resultUrls = (List<Map<String, String>>) output.get("results");
                        if (resultUrls != null) {
                            for (Map<String, String> r : resultUrls) {
                                results.add(GenerationResult.builder()
                                        .url(r.get("url"))
                                        .format("png")
                                        .width(1024)
                                        .height(1024)
                                        .build());
                            }
                        }
                        log.info("Qwen Image done: taskId={}, results={}", taskId, results.size());
                        return results;
                    } else if ("FAILED".equals(taskStatus)) {
                        throw new RuntimeException("Qwen Image task failed: " + output.get("message"));
                    }
                    // PENDING / RUNNING → 继续轮询
                }
            }

            throw new RuntimeException("Qwen Image task timeout after " + (maxRetries * 800 / 1000) + "s");

        } catch (Exception e) {
            log.error("Qwen Image generation failed: {}", e.getMessage());
            throw new RuntimeException("通义万相调用失败: " + e.getMessage());
        }
    }

    @Override
    public TaskStatus queryStatus(String externalTaskId) {
        try {
            String effectiveApiKey = configService.getApiKey("qwen-image", apiKey);
            String statusUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + externalTaskId;
            Request request = new Request.Builder()
                    .url(statusUrl).get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = objectMapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) resp.get("output");
                String status = (String) output.get("task_status");

                TaskStatus.State state = switch (status) {
                    case "SUCCEEDED" -> TaskStatus.State.DONE;
                    case "FAILED" -> TaskStatus.State.FAILED;
                    case "RUNNING" -> TaskStatus.State.PROCESSING;
                    default -> TaskStatus.State.PENDING;
                };

                return TaskStatus.builder().state(state).progress(50).build();
            }
        } catch (Exception e) {
            return TaskStatus.builder().state(TaskStatus.State.FAILED).errorInfo(e.getMessage()).build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("qwen-image")) return true;
        if (configService.isEnabled("qwen")) return true;  // fallback to qwen LLM key
        return apiKey != null && !apiKey.isEmpty() && !"your-qwen-api-key".equals(apiKey);
    }
}
