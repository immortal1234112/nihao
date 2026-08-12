package com.platform.agent.chat.service;

import com.platform.agent.adapter.LLMAdapter;
import com.platform.core.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异步记忆提取器：每轮对话后提取用户长期记忆（FACT/PREFERENCE）落库。
 * 注意：@Async 线程不携带 TenantContext/UserContext（ThreadLocal 不跨线程），
 * 所有上下文必须显式传参（与 RagDocumentProcessor 同一范式）。
 * 任何失败只记录日志，不影响主对话链路。
 *
 * 解析兼容三种模型输出风格：
 *   - 纯 JSON 数组（deepseek 等）
 *   - markdown 代码块包裹（```json ... ```，agnes 等）
 *   - 逐行 JSON 片段（模型输出被截断/折行时的兜底）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryExtractProcessor {

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```");
    private static final Pattern FIELD_CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FIELD_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"(FACT|PREFERENCE)\"", Pattern.CASE_INSENSITIVE);

    private final List<LLMAdapter> llmAdapters;
    private final MemoryService memoryService;

    /** 空 = 复用会话 provider */
    @Value("${memory.extract-provider:}")
    private String extractProvider;

    @Value("${memory.extract-max-tokens:1024}")
    private int extractMaxTokens;

    @Async("memoryExecutor")
    public void extractAsync(String userId, String tenantId, String provider,
                             String userMessage, String assistantReply, String conversationId) {
        try {
            String effectiveProvider = extractProvider != null && !extractProvider.isBlank()
                    ? extractProvider : provider;
            LLMAdapter adapter = llmAdapters.stream()
                    .filter(a -> a.provider().equalsIgnoreCase(effectiveProvider) && a.healthCheck())
                    .findFirst().orElse(null);
            if (adapter == null) {
                log.warn("Memory extract skipped: provider {} unavailable", effectiveProvider);
                return;
            }

            String prompt = "你是记忆提取器。根据下面这轮对话，提取关于用户的可长期记住的信息："
                    + "FACT=客观事实（如用户名字、职业），PREFERENCE=偏好（如喜欢简洁回答）。"
                    + "只提取本轮对话中明确表达且**新出现**的信息（用户重复之前已说过的内容不要输出），不要臆测，"
                    + "每条一句话（20字以内），最多 3 条。"
                    + "只输出纯 JSON 数组，不要 markdown 代码块、不要任何解释文字，"
                    + "格式：[{\"type\":\"FACT\",\"content\":\"...\"}]，没有可提取内容则输出 []。\n"
                    + "用户说：" + userMessage + "\n"
                    + "助手说：" + assistantReply;

            String raw = adapter.chat("", prompt, Map.of("maxTokens", extractMaxTokens));
            saveParsed(userId, tenantId, conversationId, raw);
        } catch (Exception e) {
            log.warn("Memory extract failed (ignored): {}", e.getMessage());
        }
    }

    // ==================== 解析（三级降级，兼容不同模型输出风格） ====================

    private void saveParsed(String userId, String tenantId, String conversationId, String raw) {
        if (raw == null || raw.isBlank()) return;

        // 1. 剥 markdown 代码块围栏（```json ... ```），取第一个围栏内容
        Matcher fence = FENCE.matcher(raw);
        String jsonCandidate = fence.find() ? fence.group(1) : raw;

        // 2. 尝试整体 JSON 数组解析
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) JsonUtils.getMapper()
                    .readValue(jsonCandidate, List.class);
            for (Map<String, Object> item : items) {
                Object content = item.get("content");
                Object type = item.get("type");
                if (content != null && !String.valueOf(content).isBlank()) {
                    memoryService.saveExtracted(userId, tenantId, conversationId,
                            String.valueOf(content).trim(),
                            type != null ? String.valueOf(type) : "FACT");
                }
            }
            return;
        } catch (Exception e) {
            log.debug("Memory extract JSON parse failed, fallback to regex lines: {}", e.getMessage());
        }

        // 3. 逐行正则兜底（模型输出折行/被截断时）：按 content/type 字段提取，不依赖字段顺序
        List<String[]> found = new ArrayList<>();   // [content, type]
        for (String line : jsonCandidate.split("\n")) {
            String trimmed = line.trim();
            // 跳过纯符号行（``` [ ] { } , 空 等）——防止围栏/括号被当成内容存库
            if (trimmed.isEmpty() || !trimmed.matches(".*[\\u4e00-\\u9fa5\\w].*")) continue;

            Matcher cm = FIELD_CONTENT.matcher(trimmed);
            if (cm.find()) {
                String content = cm.group(1);
                Matcher tm = FIELD_TYPE.matcher(trimmed);
                String type = tm.find() ? tm.group(1) : "FACT";
                if (!content.isBlank()) {
                    found.add(new String[]{content, type});
                }
            } else if (trimmed.startsWith("[") || trimmed.startsWith("]")) {
                continue;
            } else if (trimmed.startsWith("{") || trimmed.endsWith("}") || trimmed.startsWith("}")) {
                continue;
            } else if (trimmed.length() >= 4 && trimmed.length() <= 40) {
                // 形如 {"content":"...","type":"FACT"} 中 content 值被切掉一半时，按剩余纯文本兜底
                String clean = trimmed.replaceAll("[{}\"\\[\\],]", "").trim();
                if (!clean.isBlank() && !clean.matches(".*(type|content).*")) {
                    found.add(new String[]{clean, "FACT"});
                }
            }
        }
        for (String[] item : found) {
            memoryService.saveExtracted(userId, tenantId, conversationId, item[0], item[1]);
        }
    }
}
