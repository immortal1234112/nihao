package com.platform.image.adapter.openai;

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
 * OpenAI DALL-E 图片生成适配器。
 * 通过 OpenAI API 调用: https://api.openai.com/v1/images/generations
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   openai-image:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     model: dall-e-3
 *     size: 1024x1024
 *     quality: standard
 *
 * 可用模型: dall-e-3, dall-e-2
 */
@Slf4j
@Component
public class DalleAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${openai-image.api-key:${openai.api-key:}}")
    private String apiKey;

    @Value("${openai-image.model:dall-e-3}")
    private String modelName;

    @Value("${openai-image.size:1024x1024}")
    private String defaultSize;

    @Value("${openai-image.quality:standard}")
    private String quality;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public DalleAdapter(ModelConfigService configService) {
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
                .supportedResolutions(List.of("1024x1024", "1792x1024", "1024x1792"))
                .supportedStyles(List.of("realistic"))
                .supportedFormats(List.of("png", "webp"))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        String size = request.getSize() != null ? request.getSize() : defaultSize;
        String q = request.getQuality() != null ? request.getQuality() : quality;
        int n = request.getCount() > 0 ? request.getCount() : 1;
        String effectiveApiKey = configService.getApiKey("dalle", apiKey);
        String effectiveModel = configService.getModel("dalle", modelName);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", request.getPrompt());
            body.put("n", n);
            body.put("size", size);
            body.put("quality", q);
            body.put("response_format", "url");

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url("https://api.openai.com/v1/images/generations")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("DALL-E API error " + response.code() + ": " + respBody);
                }

                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                if (data != null) {
                    for (Map<String, Object> item : data) {
                        results.add(GenerationResult.builder()
                                .url((String) item.get("url"))
                                .format("png")
                                .width(parseWidth(size))
                                .height(parseHeight(size))
                                .build());
                    }
                }
            }

            log.info("DALL-E generation done: {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("DALL-E generation failed: {}", e.getMessage());
            throw new RuntimeException("DALL-E调用失败: " + e.getMessage());
        }
    }

    @Override
    public TaskStatus queryStatus(String externalTaskId) {
        // DALL-E is synchronous, so the task is always done once generate() returns
        return TaskStatus.builder()
                .state(TaskStatus.State.DONE)
                .progress(100)
                .build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("dalle")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-openai-api-key".equals(apiKey);
    }

    private int parseWidth(String size) {
        if (size == null) return 1024;
        String[] parts = size.split("x");
        return parts.length >= 1 ? Integer.parseInt(parts[0]) : 1024;
    }

    private int parseHeight(String size) {
        if (size == null) return 1024;
        String[] parts = size.split("x");
        return parts.length >= 2 ? Integer.parseInt(parts[1]) : 1024;
    }
}
