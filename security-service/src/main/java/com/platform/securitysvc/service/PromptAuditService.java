package com.platform.securitysvc.service;

import com.platform.feign.dto.AuditResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Prompt 审核服务 —— 敏感词过滤。
 * 敏感词列表从 Nacos 配置动态刷新。
 */
@Slf4j
@Service
@RefreshScope
public class PromptAuditService {

    @Value("${security.sensitive-words:}")
    private String sensitiveWordsConfig;

    /**
     * 审核 Prompt 文本。
     */
    public AuditResult audit(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return new AuditResult(false, "Prompt 不能为空");
        }

        if (sensitiveWordsConfig == null || sensitiveWordsConfig.isEmpty()) {
            return new AuditResult(true, "通过");
        }

        List<String> sensitiveWords = Arrays.asList(sensitiveWordsConfig.split(","));
        String lowerPrompt = prompt.toLowerCase();

        for (String word : sensitiveWords) {
            String trimmed = word.trim().toLowerCase();
            if (!trimmed.isEmpty() && lowerPrompt.contains(trimmed)) {
                log.warn("Sensitive word detected: '{}' in prompt", trimmed);
                return new AuditResult(false, "Prompt 包含敏感词: " + trimmed);
            }
        }

        return new AuditResult(true, "通过");
    }
}
