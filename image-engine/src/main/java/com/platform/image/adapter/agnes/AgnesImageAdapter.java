package com.platform.image.adapter.agnes;

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
 * Agnes Image 2.1 Flash 图片生成适配器。
 * OpenAI 兼容格式，endpoint: https://apihub.agnes-ai.com/v1/images/generations
 * 模型: agnes-image-2.1-flash（当前 $0/张）
 *
 * 注意（文档要点）:
 *   - response_format 必须放在 extra_body 内（顶层会被忽略）
 *   - size 推荐档位制（1K/2K/3K/4K）并配合 ratio；精确尺寸如 1024x768 也兼容（会被标准化）
 *   - 图生图/多图合成：extra_body.image 传图片 URL 数组（1 张 = 图生图，多张 = 多图合成）。
 *     注意 URL 必须公网可达且免鉴权——Agnes 服务器要主动拉取这张图，localhost/带登录权限的地址都会失败。
 *
 * 配置示例（管理后台模型配置页或 Nacos）:
 *   agnes-image:
 *     api-key: YOUR_API_KEY
 *     model: agnes-image-2.1-flash
 */
@Slf4j
@Component
public class AgnesImageAdapter implements ImageGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Value("${agnes-image.api-key:${agnes.api-key:}}")
    private String apiKey;

    @Value("${agnes-image.model:agnes-image-2.1-flash}")
    private String modelName;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public AgnesImageAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(360, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public ModelCapability capability() {
        return ModelCapability.builder()
                .supportedResolutions(List.of("1K", "2K", "1024x1024", "2048x2048"))
                .supportedStyles(List.of("realistic"))
                .supportedFormats(List.of("png", "jpeg", "webp"))
                .maxBatchSize(1)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<GenerationResult> generate(GenerateRequest request) {
        List<GenerationResult> results = new ArrayList<>();
        try {
            String effectiveApiKey = configService.getApiKey("agnes-image", apiKey);
            String effectiveModel = configService.getModel("agnes-image", modelName);

            // size 档位化 + ratio（Agnes 推荐 1K/2K + 宽高比，精确尺寸会被标准化）
            String size = resolveSize(request.getSize());
            String ratio = resolveRatio(request.getAspectRatio());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", request.getPrompt());
            body.put("size", size);
            if (ratio != null) body.put("ratio", ratio);

            // 注意：response_format 必须放在 extra_body（顶层写法不生效）
            Map<String, Object> extraBody = new LinkedHashMap<>();
            extraBody.put("response_format", "url");
            // 图生图/多图合成：extra_body.image 传图片 URL 数组（1 张=图生图，多张=多图合成）。
            // URL 必须公网可达且免鉴权（Agnes 服务器主动拉取；localhost/需登录的地址会失败）
            if (request.getReferenceImages() != null && !request.getReferenceImages().isEmpty()) {
                extraBody.put("image", request.getReferenceImages());
                log.info("Agnes Image img2img: {} reference image(s) attached", request.getReferenceImages().size());
            }
            body.put("extra_body", extraBody);

            String json = objectMapper.writeValueAsString(body);

            Request httpRequest = new Request.Builder()
                    .url("https://apihub.agnes-ai.com/v1/images/generations")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Agnes Image API error " + response.code() + ": " + respBody);
                }

                Map<String, Object> resp = objectMapper.readValue(respBody, Map.class);
                List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                if (data != null) {
                    for (Map<String, Object> item : data) {
                        results.add(GenerationResult.builder()
                                .url((String) item.get("url"))
                                .format("png")
                                .width(resolveDimension(size, ratio)[0])
                                .height(resolveDimension(size, ratio)[1])
                                .build());
                    }
                }
            }

            log.info("Agnes Image generation done: {} results (size={}, ratio={})", results.size(), size, ratio);
            return results;

        } catch (Exception e) {
            log.error("Agnes Image generation failed: {}", e.getMessage());
            throw new RuntimeException("Agnes Image 调用失败: " + e.getMessage());
        }
    }

    @Override
    public TaskStatus queryStatus(String externalTaskId) {
        // Agnes Image 同步返回，任务在 generate() 返回后即完成
        return TaskStatus.builder()
                .state(TaskStatus.State.DONE)
                .progress(100)
                .build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled("agnes-image")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-agnes-api-key".equals(apiKey);
    }

    /** 平台尺寸写法 → Agnes 档位（512/768 规格按 1K 处理；1K/2K 等档位直接透传） */
    private String resolveSize(String size) {
        if (size == null || size.isBlank()) return "1K";
        String s = size.trim().toUpperCase();
        if (s.equals("1K") || s.equals("2K") || s.equals("3K") || s.equals("4K")) return s;
        if (s.equals("2048X2048")) return "2K";
        return "1K";
    }

    /** 宽高比：平台 1:1/16:9/9:16/4:3/3:4/2:3/3:2/21:9 与 Agnes 一致，直接透传 */
    private String resolveRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) return null;
        String r = aspectRatio.trim();
        Set<String> supported = Set.of("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9");
        return supported.contains(r) ? r : null;
    }

    /** 档位 × 宽高比 → 输出尺寸（来自 Agnes 文档输出尺寸表） */
    private int[] resolveDimension(String size, String ratio) {
        boolean is2k = "2K".equals(size);
        switch (ratio == null ? "1:1" : ratio) {
            case "3:4":  return is2k ? new int[]{1728, 2304} : new int[]{864, 1152};
            case "4:3":  return is2k ? new int[]{2304, 1728} : new int[]{1152, 864};
            case "16:9": return is2k ? new int[]{2624, 1472} : new int[]{1312, 736};
            case "9:16": return is2k ? new int[]{1472, 2624} : new int[]{736, 1312};
            case "2:3":  return is2k ? new int[]{1664, 2496} : new int[]{832, 1248};
            case "3:2":  return is2k ? new int[]{2496, 1664} : new int[]{1248, 832};
            case "21:9": return is2k ? new int[]{3136, 1344} : new int[]{1568, 672};
            default:     return is2k ? new int[]{2048, 2048} : new int[]{1024, 1024};
        }
    }
}
