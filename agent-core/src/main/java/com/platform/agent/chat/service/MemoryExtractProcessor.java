package com.platform.agent.chat.service;

import com.platform.agent.adapter.ChatMessage;
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
 * 异步记忆提取器：每轮对话后提取用户短期记忆（FACT/PREFERENCE）落库（长期板块仅管理页手动配置，AI 不自动写入）。
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

    /** 用户显式记忆指令："记住 X" / "请记住 X" / "帮我记住 X" / "以后记得 X" / "记得 X" */
    private static final List<Pattern> MEMORY_INSTRUCTIONS = List.of(
            Pattern.compile("(?:请|帮我)?记住[:：\\s]*(.+?)(?:[。！？!?；;]|$)"),
            Pattern.compile("(?:以后|下次)?(?:请|帮我)?记得(?:[:：\\s])*(.+?)(?:[。！？!?；;]|$)"),
            // 刻意不含"长期"：强制落库只进短期板块，"存进长期记忆"这类措辞不命中（避免存错板块）
            Pattern.compile("(?:请|帮我)?(?:把它|这段话)?存进?(?:我的)?记忆(?:里|中)?[:：\\s]*(.+?)(?:[。！？!?；;]|$)")
    );

    private final List<LLMAdapter> llmAdapters;
    private final MemoryService memoryService;

    /** 空 = 复用会话 provider */
    @Value("${memory.extract-provider:}")
    private String extractProvider;

    @Value("${memory.extract-max-tokens:1024}")
    private int extractMaxTokens;

    /** 提取时拼接的最近对话轮数（含当前轮），多轮上下文才能捕捉到分散表达的关键信息 */
    private static final int CONTEXT_ROUNDS = 6;
    /** 单条消息拼入提取上下文的字符上限（防 prompt 膨胀） */
    private static final int CONTEXT_MSG_MAX_CHARS = 300;

    @Async("memoryExecutor")
    public void extractAsync(String userId, String tenantId, String provider,
                             String userMessage, String assistantReply, String conversationId,
                             List<ChatMessage> recentHistory) {
        try {
            // 0. 显式记忆指令（"记住 X"/"记得 X"）：不依赖模型判断，直接强制落库（用户指令优先级最高）
            String instructed = extractInstruction(userMessage);
            if (instructed != null) {
                memoryService.saveForced(userId, tenantId, conversationId, instructed, "FACT");
            }

            String effectiveProvider = extractProvider != null && !extractProvider.isBlank()
                    ? extractProvider : provider;
            LLMAdapter adapter = llmAdapters.stream()
                    .filter(a -> a.provider().equalsIgnoreCase(effectiveProvider) && a.healthCheck())
                    .findFirst().orElse(null);
            if (adapter == null) {
                log.warn("Memory extract skipped: provider {} unavailable", effectiveProvider);
                return;
            }

            // 1. 拼最近多轮对话（含当前轮）作为提取上下文 —— 关键信息常分散在多轮里，单轮提取会漏
            StringBuilder dialogue = new StringBuilder();
            if (recentHistory != null) {
                int from = Math.max(0, recentHistory.size() - (CONTEXT_ROUNDS - 1));
                for (int i = from; i < recentHistory.size(); i++) {
                    appendDialogueLine(dialogue, recentHistory.get(i));
                }
            }
            appendDialogueLine(dialogue, "user", userMessage);
            appendDialogueLine(dialogue, "assistant", assistantReply);

            String prompt = "你是记忆提取器。从下面这段对话中**自动捕捉**关于用户的关键信息，"
                    + "不需要用户明确要求记住，主动提取值得长期保存的内容：\n"
                    + "FACT=客观事实（身份、背景、经历、日期、约定、正在做的事情）\n"
                    + "PREFERENCE=偏好（喜好、习惯、称呼、相处方式、说话风格）\n"
                    + "重点捕捉：用户透露的个人信息、喜好与习惯、对助手的称呼或人设约定、当前关注的重点事项。\n"
                    + "忽略寒暄与无信息量的内容（如\"你好\"\"在吗\"\"哈哈\"\"好的\"）。\n"
                    + "每条一句话（60字以内），最多 5 条，按重要程度排序。\n"
                    + "只输出纯 JSON 数组，不要 markdown 代码块、不要任何解释文字，"
                    + "格式：[{\"type\":\"FACT\",\"content\":\"...\"}]，没有可提取内容则输出 []。\n"
                    + "对话：\n" + dialogue;

            String raw = adapter.chat("", prompt, Map.of("maxTokens", extractMaxTokens));
            saveParsed(userId, tenantId, conversationId, raw);
        } catch (Exception e) {
            log.warn("Memory extract failed (ignored): {}", e.getMessage());
        }
    }

    private void appendDialogueLine(StringBuilder sb, ChatMessage msg) {
        appendDialogueLine(sb, msg.role(), msg.content());
    }

    private void appendDialogueLine(StringBuilder sb, String role, String content) {
        if (content == null || content.isBlank()) return;
        String label = "assistant".equals(role) ? "助手" : "用户";
        String text = content;
        if (text.length() > CONTEXT_MSG_MAX_CHARS) {
            text = text.substring(0, CONTEXT_MSG_MAX_CHARS) + "…";
        }
        sb.append(label).append("：").append(text).append("\n");
    }

    // ==================== 显式指令提取 ====================

    /**
     * 从用户消息中提取"记住 X"指令的 X（多条取第一条；无指令返回 null）。
     * 匹配后调用方强制落库（saveForced），与模型提取结果互不影响。
     */
    private String extractInstruction(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;
        for (Pattern p : MEMORY_INSTRUCTIONS) {
            Matcher m = p.matcher(userMessage);
            if (m.find()) {
                String content = m.group(1).trim();
                // 过滤仅剩语气词/无实义的情况（"记住了" 等）
                if (content.length() >= 2 && content.matches(".*[\\u4e00-\\u9fa5\\w].*")) {
                    return content;
                }
            }
        }
        return null;
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
