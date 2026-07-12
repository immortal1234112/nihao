package com.platform.image.meme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.core.config.ModelConfigService;
import com.platform.image.adapter.qwen.QwenImageAdapter;
import com.platform.image.meme.model.MemeTaskState;
import com.platform.image.meme.model.MemeTaskState.Status;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 表情包生成核心服务 —— 异步编排：拉模板 → 千问写 Prompt → 通义万相出图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemeService {

    private final MemeTemplateClient templateClient;
    private final MemeTaskStore taskStore;
    private final QwenImageAdapter qwenImageAdapter;
    private final ModelConfigService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${qwen.api-key:}")
    private String qwenApiKey;

    @Value("${qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String qwenBaseUrl;

    @Value("${meme.qwen-prompt-model:qwen-turbo}")
    private String qwenModel;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    /**
     * 异步执行表情包生成流水线。
     */
    @Async("memeExecutor")
    public void executeAsync(String taskId, String userText) {
        try {
            taskStore.updateStatus(taskId, Status.PROCESSING);
            log.info("Meme task {} started, text: {}", taskId, userText);

            // Step 1: 获取模板
            List<Map<String, String>> templates = templateClient.getTemplates();

            // Step 2: 千问 → 选模板 + 写梗 + 绘图 prompt
            QwenPromptResult promptResult = callQwenForPrompt(userText, templates);
            taskStore.updateStatus(taskId, Status.PROCESSING);
            log.info("Meme task {} prompt done: memeText={}, imagePrompt={}",
                    taskId, promptResult.memeText, promptResult.imagePrompt);

            // Step 3: 通义万相出图（重试 1 次）
            GenerationResult imageResult = generateImageWithRetry(promptResult.imagePrompt, 1);
            String imageUrl = imageResult.getUrl();
            if (imageUrl == null && imageResult.getBase64Data() != null) {
                imageUrl = "data:image/png;base64," + imageResult.getBase64Data();
            }

            // Step 4: 更新结果
            taskStore.updateDone(taskId, imageUrl, promptResult.memeText);
            log.info("Meme task {} done, imageUrl={}", taskId, imageUrl);

        } catch (Exception e) {
            log.error("Meme task {} failed: {}", taskId, e.getMessage(), e);
            taskStore.updateFailed(taskId, e.getMessage());
        }
    }

    // ==================== 千问 Prompt ====================

    private QwenPromptResult callQwenForPrompt(String userText, List<Map<String, String>> templates) {
        String templateDesc = templates.stream()
                .map(t -> String.format("  - id=%s, name=%s, description=%s, 文字行数=%s",
                        t.get("id"), t.get("name"),
                        t.getOrDefault("description", t.get("name")),
                        t.getOrDefault("lines", "2")))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                你是一个表情包创意助手。用户给你一句话，你需要：
                1. 从下列模板中选最合适的 1 个
                2. 根据用户的话写出梗（meme 上的文字，简短幽默，行数匹配模板）
                3. 将"模板描述 + 梗文字"转成通义万相能理解的绘图提示词（中文，50字以内，描述画面细节和文字位置）

                模板列表：
                %s

                只输出 JSON，不要加任何其他内容：
                {"templateId":"xxx","memeText":"xxx","imagePrompt":"xxx"}
                """.formatted(templateDesc);

        String response = callQwenChat(systemPrompt, userText);
        return parsePromptResult(response);
    }

    private String callQwenChat(String systemPrompt, String userMessage) {
        try {
            String effectiveApiKey = configService.getApiKey("qwen", qwenApiKey);
            String effectiveBaseUrl = configService.getBaseUrl("qwen", qwenBaseUrl);
            String effectiveModel = configService.getModel("qwen", qwenModel);

            List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("messages", messages);
            body.put("temperature", 0.9);
            body.put("max_tokens", 512);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(effectiveBaseUrl + "/chat/completions")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IOException("Qwen API error " + response.code() + ": " + respBody);
                }
                return extractContent(respBody);
            }
        } catch (IOException e) {
            throw new RuntimeException("千问调用失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String respBody) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(respBody, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        }
        throw new RuntimeException("千问返回为空");
    }

    private QwenPromptResult parsePromptResult(String content) {
        try {
            // 去除可能的 markdown 代码块包裹
            String json = content.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```\\w*\\n?", "").replace("```", "").trim();
            }
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return new QwenPromptResult(
                    (String) map.get("templateId"),
                    (String) map.get("memeText"),
                    (String) map.get("imagePrompt")
            );
        } catch (Exception e) {
            log.error("Failed to parse Qwen prompt result: {}", content);
            throw new RuntimeException("千问返回格式异常: " + content.substring(0, Math.min(100, content.length())));
        }
    }

    // ==================== 通义万相出图 ====================

    private GenerationResult generateImageWithRetry(String imagePrompt, int maxRetries) {
        Exception lastEx = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                GenerateRequest req = GenerateRequest.builder()
                        .prompt(imagePrompt)
                        .size("1024x1024")
                        .count(1)
                        .build();
                List<GenerationResult> results = qwenImageAdapter.generate(req);
                if (results != null && !results.isEmpty()) {
                    return results.get(0);
                }
                throw new RuntimeException("通义万相返回空结果");
            } catch (Exception e) {
                lastEx = e;
                if (i < maxRetries) {
                    log.warn("Meme image gen retry {}/{}: {}", i + 1, maxRetries, e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new RuntimeException("通义万相出图失败（已重试" + maxRetries + "次）: " +
                (lastEx != null ? lastEx.getMessage() : "unknown"));
    }

    // ==================== 内部类 ====================

    private record QwenPromptResult(String templateId, String memeText, String imagePrompt) {}
}
