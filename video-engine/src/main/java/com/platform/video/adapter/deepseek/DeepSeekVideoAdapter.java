package com.platform.video.adapter.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import com.platform.video.model.VideoModelCapability;
import com.platform.video.model.VideoTaskStatus;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 视频生成适配器 —— 提示词增强 + 委托真实视频模型生成。
 *
 * 工作流程：
 * 1. 用户选择 DeepSeek，提交视频生成请求
 * 2. DeepSeek Chat API 将简短提示词增强为详细的视频制作脚本
 * 3. 增强后的提示词自动交给可用的视频模型（Runway / Kling / Pika / Luma）生成真正的视频
 * 4. 返回真实视频 URL，用户可下载
 *
 * 配置示例:
 *   deepseek:
 *     api-key: sk-xxxxxxxx
 *     base-url: https://api.deepseek.com
 *     model: deepseek-chat
 */
@Slf4j
@Component
public class DeepSeekVideoAdapter implements VideoGenerationAdapter {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ModelConfigService configService;

    @Autowired
    private ApplicationContext applicationContext;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    @Value("${platform.require-api-key:true}")
    private boolean requireApiKey;

    public DeepSeekVideoAdapter(ModelConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public VideoModelCapability capability() {
        return VideoModelCapability.builder()
                .maxDuration(30)
                .supportedResolutions(List.of("1920x1080", "1280x768", "1024x576"))
                .supportedFps(List.of(24, 30))
                .maxBatchSize(1)
                .build();
    }

    /**
     * 文生视频：DeepSeek 增强提示词 → 委托真实视频模型生成视频。
     */
    @Override
    public VideoGenerationResult textToVideo(VideoGenerateRequest request) {
        // 1. DeepSeek 增强提示词
        String enhanced = enhancePrompt(request.getPrompt());
        log.info("DeepSeek enhanced prompt ({} → {} chars)", request.getPrompt().length(), enhanced.length());

        // 2. 找真实视频模型
        VideoGenerationAdapter realAdapter = findRealAdapter();
        if (realAdapter == null) {
            throw new RuntimeException("DeepSeek 提示词已增强，但没有可用的视频生成模型（请配置 Runway/Kling/Pika/Luma 任一 API Key）");
        }

        // 3. 委托生成真实视频
        log.info("DeepSeek → delegating to: {}", realAdapter.getClass().getSimpleName());
        VideoGenerateRequest enhancedReq = VideoGenerateRequest.builder()
                .prompt(enhanced)
                .duration(request.getDuration())
                .fps(request.getFps())
                .resolution(request.getResolution())
                .style(request.getStyle())
                .sourceImageUrl(request.getSourceImageUrl())
                .negativePrompt(request.getNegativePrompt())
                .build();

        VideoGenerationResult result = realAdapter.textToVideo(enhancedReq);
        log.info("DeepSeek pipeline done: videoUrl={}", result.getVideoUrl());
        return result;
    }

    /**
     * 图生视频：DeepSeek 增强提示词 → 委托真实视频模型。
     */
    @Override
    public VideoGenerationResult imageToVideo(VideoGenerateRequest request) {
        String enhanced = request.getPrompt() != null
                ? enhancePrompt(request.getPrompt())
                : "Smooth, natural animation with cinematic quality";

        VideoGenerationAdapter realAdapter = findRealAdapter();
        if (realAdapter == null) {
            throw new RuntimeException("DeepSeek 提示词已增强，但没有可用的视频生成模型（请配置 Runway/Kling/Pika/Luma 任一 API Key）");
        }

        log.info("DeepSeek → delegating image-to-video to: {}", realAdapter.getClass().getSimpleName());
        VideoGenerateRequest enhancedReq = VideoGenerateRequest.builder()
                .prompt(enhanced)
                .duration(request.getDuration())
                .fps(request.getFps())
                .resolution(request.getResolution())
                .style(request.getStyle())
                .sourceImageUrl(request.getSourceImageUrl())
                .negativePrompt(request.getNegativePrompt())
                .build();

        return realAdapter.imageToVideo(enhancedReq);
    }

    @Override
    public VideoTaskStatus queryStatus(String externalTaskId) {
        VideoGenerationAdapter real = findRealAdapter();
        return real != null ? real.queryStatus(externalTaskId)
                : VideoTaskStatus.builder().state(VideoTaskStatus.State.PENDING).build();
    }

    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;
        if (configService.isEnabled("deepseek")) return true;
        return apiKey != null && !apiKey.isEmpty() && !"your-deepseek-api-key".equals(apiKey);
    }

    // ==================== 内部方法 ====================

    /** 找除自己外第一个可用的视频适配器 */
    private VideoGenerationAdapter findRealAdapter() {
        Map<String, VideoGenerationAdapter> beans = applicationContext.getBeansOfType(VideoGenerationAdapter.class);
        for (VideoGenerationAdapter a : beans.values()) {
            if (a == this) continue;
            if (a.healthCheck()) return a;
        }
        return null;
    }

    /** 用 DeepSeek Chat 把简短提示词扩展为详细视频脚本 */
    @SuppressWarnings("unchecked")
    private String enhancePrompt(String prompt) {
        try {
            String key = configService.getApiKey("deepseek", apiKey);
            String url = configService.getBaseUrl("deepseek", baseUrl);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content",
                            "You are a professional film director. Transform the user's short prompt "
                                    + "into a detailed, cinematic video description in English. "
                                    + "Include: specific visual details, lighting, camera angles, "
                                    + "color palette, atmosphere, movement, and scene composition. "
                                    + "Output ONLY the enhanced description, NO explanations, NO meta-text."),
                    Map.of("role", "user", "content",
                            "Enhance this into a cinematic video prompt: " + prompt)
            ));
            body.put("max_tokens", 400);
            body.put("temperature", 0.8);

            String json = objectMapper.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(url + "/v1/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("DeepSeek enhance failed, using original prompt");
                    return prompt;
                }
                String b = resp.body() != null ? resp.body().string() : "";
                Map<String, Object> map = objectMapper.readValue(b, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                    String enhanced = (String) msg.get("content");
                    if (enhanced != null && !enhanced.isBlank() && enhanced.length() > prompt.length()) {
                        return enhanced.trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DeepSeek enhance error: {}", e.getMessage());
        }
        return prompt;
    }
}
