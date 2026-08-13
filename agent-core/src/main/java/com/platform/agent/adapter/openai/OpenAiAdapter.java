package com.platform.agent.adapter.openai;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI 大模型适配器。
 * OpenAI API 兼容格式，endpoint: https://api.openai.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   openai:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://api.openai.com
 *     model: gpt-4o
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Component
public class OpenAiAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Value("${openai.max-tokens:4096}")
    private int maxTokens;

    @Value("${openai.temperature:0.7}")
    private double temperature;

    public OpenAiAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    protected String apiKey() {
        return apiKey;
    }

    @Override
    protected String baseUrl() {
        return baseUrl;
    }

    @Override
    protected String model() {
        return model;
    }

    @Override
    protected int maxTokens() {
        return maxTokens;
    }

    @Override
    protected double temperature() {
        return temperature;
    }

    @Override
    protected String placeholderApiKey() {
        return "your-openai-api-key";
    }
}
