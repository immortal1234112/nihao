package com.platform.image.adapter.midjourney;

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
 * Midjourney 图片生成适配器。
 * 通过 Midjourney API 调用（概念性 imagine API 模式）。
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   midjourney:
 *     api-key: mj-xxxxxxxxxxxxxxxx
 *     base-url: https://api.midjourney.com
 *     model: midjourney-v6
 *
 * 可用模型: midjourney-v6, midjourney-v5.2 等
 */
@Slf4j
@Component
public class MidjourneyAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${midjourney.api-key:}")
    private String apiKey;

    @Value("${midjourney.base-url:https://api.midjourney.com}")
    private String baseUrl;

    @Value("${midjourney.model:midjourney-v6}")
    private String modelName;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public MidjourneyAdapter(ModelConfigService configService) {
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
                .supportedResolutions(List.of("1024x1024"))
                .supportedStyles(List.of("realistic", "anime", "pixel-art", "3d"))
                .supportedFormats(List.of("png"))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        String effectiveApiKey = configService.getApiKey("midjourney", apiKey);
        String effectiveBaseUrl = configService.getBaseUrl("midjourney", baseUrl);
        String effectiveModel = configService.getModel("midjourney", modelName);

        try {
            // Step 1: Submit imagine request
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("model", effectiveModel);
            body.put("aspect_ratio", "1:1");

            String json = objectMapper.writeValueAsString(body);

            Request imagineRequest = new Request.Builder()
                    .url(effectiveBaseUrl + "/v1/imagine")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            String taskId;
            try (Response response = httpClient.newCall(imagineRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Midjourney API error " + response.code() + ": " + respBody);
                }
                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                taskId = (String) resp.get("task_id");
            }

            log.info("Midjourney task submitted: taskId={}", taskId);

            // Step 2: Poll for result
            int maxRetries = 60;
            for (int i = 0; i < maxRetries; i++) {
                Thread.sleep(3000);

                Request pollRequest = new Request.Builder()
                        .url(effectiveBaseUrl + "/v1/tasks/" + taskId)
                        .get()
                        .header("Authorization", "Bearer " + effectiveApiKey)
                        .build();

                try (Response response = httpClient.newCall(pollRequest).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                    String status = (String) resp.get("status");

                    if ("completed".equals(status)) {
                        String imageUrl = (String) resp.get("image_url");
                        results.add(GenerationResult.builder()
                                .url(imageUrl)
                                .format("png")
                                .width(1024)
                                .height(1024)
                                .build());
                        log.info("Midjourney task completed: taskId={}", taskId);
                        return results;
                    } else if ("failed".equals(status)) {
                        String errorMsg = (String) resp.get("error");
                        throw new RuntimeException("Midjourney task failed: " + errorMsg);
                    }
                    // "pending" / "processing" -> continue polling
                }
            }

            throw new RuntimeException("Midjourney task timeout after " + (maxRetries * 3) + "s");

        } catch (Exception e) {
            log.error("Midjourney generation failed: {}", e.getMessage());
            throw new RuntimeException("Midjourney调用失败: " + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public TaskStatus queryStatus(String externalTaskId) {
        try {
            String effectiveApiKey = configService.getApiKey("midjourney", apiKey);
            String effectiveBaseUrl = configService.getBaseUrl("midjourney", baseUrl);
            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/v1/tasks/" + externalTaskId)
                    .get()
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                String status = (String) resp.get("status");

                TaskStatus.State state = switch (status) {
                    case "completed" -> TaskStatus.State.DONE;
                    case "failed" -> TaskStatus.State.FAILED;
                    case "processing" -> TaskStatus.State.PROCESSING;
                    default -> TaskStatus.State.PENDING;
                };

                return TaskStatus.builder().state(state).progress(50).build();
            }
        } catch (Exception e) {
            return TaskStatus.builder()
                    .state(TaskStatus.State.FAILED)
                    .errorInfo(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("midjourney")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-midjourney-api-key".equals(apiKey);
    }
}
