package com.platform.agent.service;

import com.platform.agent.adapter.LLMAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提示词增强服务 —— 中文提示词自动翻译/润色为英文。
 * <p>
 * 生成模型（agnes 等）对英文提示词理解显著优于中文，而用户习惯用中文创作。
 * 本服务在任务提交时对含中文的提示词做一次 LLM 翻译+优化，用户全程无感：
 * <ul>
 *   <li>纯英文提示词直接原样返回（不浪费一次 LLM 调用）；</li>
 *   <li>LLM 不可用/调用失败 → 降级返回原文，绝不阻塞任务主链路（只记日志）；</li>
 *   <li>provider 可配置（默认 deepseek），配置的 provider 不可用时自动选第一个健康的适配器。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptEnhancerService {

    /** CJK 字符检测：含中文字符才需要翻译 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final String SYSTEM_PROMPT =
            "你是专业的 AI 绘画/视频提示词翻译优化器。把用户的中文描述翻译为高质量的英文提示词"
                    + "（供 stable diffusion / agnes 等图像视频生成模型直接使用）。要求：\n"
                    + "1. 保留用户描述中的所有具体细节：主体、场景、光线、构图、风格、动作、情绪；\n"
                    + "2. 翻译准确简洁，使用模型熟悉的美术/摄影专业术语；\n"
                    + "3. 可以补充少量提升生成质量的通用修饰（如光线、细节、构图描述），"
                    + "但不要添加用户未表达的内容；\n"
                    + "4. 只输出一句英文提示词，不要任何解释、编号、引号或 markdown 格式。";

    private final List<LLMAdapter> llmAdapters;

    /** 总开关：false 时原样返回，不调用 LLM */
    @Value("${platform.prompt-enhance.enabled:true}")
    private boolean enabled;

    /** 翻译用的模型 provider；空/不可用时自动选第一个健康的适配器 */
    @Value("${platform.prompt-enhance.provider:deepseek}")
    private String provider;

    @Value("${platform.prompt-enhance.max-tokens:1024}")
    private int maxTokens;

    /**
     * 增强提示词。无中文/未启用/适配器缺失/调用失败 → 原样返回。
     */
    public String enhance(String prompt) {
        if (!enabled || prompt == null || prompt.isBlank() || !CJK.matcher(prompt).find()) {
            return prompt;
        }
        LLMAdapter adapter = resolveAdapter();
        if (adapter == null) {
            log.warn("Prompt enhance skipped: no healthy LLM adapter available");
            return prompt;
        }
        try {
            String enhanced = adapter.chat(SYSTEM_PROMPT, prompt, Map.of("maxTokens", maxTokens));
            if (enhanced == null || enhanced.isBlank()) {
                return prompt;
            }
            String trimmed = enhanced.trim();
            log.info("Prompt enhanced: \"{}\" -> \"{}\"", truncate(prompt, 60), truncate(trimmed, 120));
            return trimmed;
        } catch (Exception e) {
            // 降级：翻译失败不影响任务提交，用原文（效果略差但可用）
            log.warn("Prompt enhance degraded, keep original: {}", e.getMessage());
            return prompt;
        }
    }

    private LLMAdapter resolveAdapter() {
        if (provider != null && !provider.isBlank()) {
            LLMAdapter preferred = llmAdapters.stream()
                    .filter(a -> a.provider().equalsIgnoreCase(provider) && a.healthCheck())
                    .findFirst().orElse(null);
            if (preferred != null) return preferred;
            log.warn("Prompt enhance provider {} not healthy, fallback to first healthy adapter", provider);
        }
        return llmAdapters.stream()
                .filter(LLMAdapter::healthCheck)
                .findFirst().orElse(null);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
