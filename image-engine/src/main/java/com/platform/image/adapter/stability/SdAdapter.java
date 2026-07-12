package com.platform.image.adapter.stability;

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
 * Stability AI Stable Diffusion 图片生成适配器。
 * 通过 Stability AI API 调用: https://api.stability.ai/v1/generation/{engine-id}/text-to-image
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   stability:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     model: stable-diffusion-xl
 *     engine-id: stable-diffusion-xl-1024-v1-0
 *
 * 可用模型: stable-diffusion-xl, stable-diffusion-v1-5 等
 */
@Slf4j
@Component
public class SdAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${stability.api-key:}")
    private String apiKey;

    @Value("${stability.model:stable-diffusion-xl}")
    private String modelName;

    @Value("${stability.engine-id:stable-diffusion-xl-1024-v1-0}")
    private String engineId;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public SdAdapter(ModelConfigService configService) {
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
                .supportedResolutions(List.of("1024x1024", "896x1152", "1152x896"))
                .supportedStyles(List.of("realistic", "anime", "fantasy", "3d-render"))
                .supportedFormats(List.of("png", "jpeg", "webp"))
                .maxBatchSize(4)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        int count = request.getCount() > 0 ? request.getCount() : 1;
        int width = 1024;
        int height = 1024;
        if (request.getSize() != null) {
            String[] parts = request.getSize().split("x");
            if (parts.length >= 2) {
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            }
        }

        String effectiveApiKey = configService.getApiKey("stability", apiKey);
        String effectiveEngineId = configService.getModel("stability", engineId);

        String stylePreset = request.getStyle() != null ? request.getStyle() : "realistic";

        try {
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("text_prompts[0][text]", request.getPrompt())
                    .addFormDataPart("text_prompts[0][weight]", "1")
                    .addFormDataPart("cfg_scale", "7")
                    .addFormDataPart("height", String.valueOf(height))
                    .addFormDataPart("width", String.valueOf(width))
                    .addFormDataPart("samples", String.valueOf(count))
                    .addFormDataPart("steps", "30")
                    .addFormDataPart("style_preset", stylePreset);

            String url = "https://api.stability.ai/v1/generation/" + effectiveEngineId + "/text-to-image";

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(multipartBuilder.build())
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Stability AI API error " + response.code() + ": " + respBody);
                }

                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                List<Map<String, Object>> artifacts = (List<Map<String, Object>>) resp.get("artifacts");
                if (artifacts != null) {
                    for (Map<String, Object> artifact : artifacts) {
                        String base64Data = (String) artifact.get("base64");
                        String finishReason = (String) artifact.get("finishReason");
                        Object seedObj = artifact.get("seed");

                        String format = "png";
                        // Use base64 data URL as the url field since we need a URL
                        String dataUrl = "data:image/" + format + ";base64," + base64Data;

                        GenerationResult.GenerationResultBuilder builder = GenerationResult.builder()
                                .url(dataUrl)
                                .base64Data(base64Data)
                                .format(format)
                                .width(width)
                                .height(height);

                        if (seedObj instanceof Number) {
                            builder.seed(((Number) seedObj).longValue());
                        }

                        results.add(builder.build());
                    }
                }
            }

            log.info("Stability AI generation done: {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Stability AI generation failed: {}", e.getMessage());
            throw new RuntimeException("Stable Diffusion调用失败: " + e.getMessage());
        }
    }

    @Override
    public TaskStatus queryStatus(String externalTaskId) {
        // Stability AI is synchronous, so the task is always done once generate() returns
        return TaskStatus.builder()
                .state(TaskStatus.State.DONE)
                .progress(100)
                .build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("stability")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-stability-api-key".equals(apiKey);
    }
}
