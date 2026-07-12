package com.platform.image.adapter.local;

import com.fasterxml.jackson.core.type.TypeReference;
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
 * 本地 Stable Diffusion (SDXL Turbo) 图片生成适配器。
 * 连接本地推理服务器 (Python FastAPI)，通过 diffusers 库生成图片。
 *
 * 本地服务器配置:
 *   local-image.base-url: 推理服务器地址，默认 http://localhost:7860
 *   local-image.model: 模型名称，默认 sdxl-turbo
 *
 * 优势:
 *   - 无需 API Key，完全离线运行
 *   - SDXL Turbo 可在 1-4 步内生成高质量图片
 *   - RTX 4060 8GB 可正常运行
 */
@Slf4j
@Component
public class LocalImageAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${local-image.base-url:http://localhost:7860}")
    private String baseUrl;

    @Value("${local-image.model:sdxl-turbo}")
    private String modelName;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public LocalImageAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return "local-sd";
    }

    @Override
    public ModelCapability capability() {
        return ModelCapability.builder()
                .supportedResolutions(List.of("512x512", "768x768", "1024x1024"))
                .supportedStyles(List.of("realistic", "anime", "fantasy", "3d-render", "sketch", "watercolor"))
                .supportedFormats(List.of("png"))
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
        if (request.getSize() != null && request.getSize().contains("x")) {
            try {
                String[] parts = request.getSize().split("x");
                width = Integer.parseInt(parts[0]);
                height = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            // Build JSON request body matching the Python server's ImageRequest schema
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("prompt", request.getPrompt());
            body.put("negative_prompt", request.getNegativePrompt() != null ? request.getNegativePrompt() : "blurry, low quality, distorted");
            body.put("width", width);
            body.put("height", height);
            body.put("num_images", count);
            body.put("steps", 4);  // SDXL Turbo only needs 1-4 steps
            body.put("guidance_scale", 0.0);  // SDXL Turbo works best without CFG
            body.put("seed", -1);
            body.put("style", request.getStyle() != null ? request.getStyle() : "realistic");

            String jsonBody = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/api/v1/generate/image")
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Local image server error " + response.code() + ": " + respBody);
                }

                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                List<String> images = (List<String>) resp.get("images");
                Object seedObj = resp.get("seed");

                if (images != null) {
                    for (String base64Data : images) {
                        GenerationResult.GenerationResultBuilder builder = GenerationResult.builder()
                                .url("data:image/png;base64," + base64Data)
                                .base64Data(base64Data)
                                .format("png")
                                .width(width)
                                .height(height);

                        if (seedObj instanceof Number) {
                            builder.seed(((Number) seedObj).longValue());
                        }

                        results.add(builder.build());
                    }
                }
            }

            log.info("Local SD generation done: {} image(s) at {}x{}", results.size(), width, height);
            return results;

        } catch (Exception e) {
            log.error("Local SD generation failed: {}", e.getMessage());
            throw new RuntimeException("本地 Stable Diffusion 调用失败: " + e.getMessage());
        }
    }

    @Override
    public TaskStatus queryStatus(String externalTaskId) {
        // Local image generation is synchronous
        return TaskStatus.builder()
                .state(TaskStatus.State.DONE)
                .progress(100)
                .build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        try {
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/api/v1/health")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("Local image server health check failed: {}", e.getMessage());
            return false;
        }
    }
}
