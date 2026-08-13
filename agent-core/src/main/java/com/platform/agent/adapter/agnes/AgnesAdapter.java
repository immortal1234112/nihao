package com.platform.agent.adapter.agnes;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agnes 大模型适配器。
 * OpenAI 兼容格式，endpoint: https://apihub.agnes-ai.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   agnes:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://apihub.agnes-ai.com
 *     model: agnes-2.5-flash
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Component
public class AgnesAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${agnes.api-key:}")
    private String apiKey;

    @Value("${agnes.base-url:https://apihub.agnes-ai.com}")
    private String baseUrl;

    @Value("${agnes.model:agnes-2.5-flash}")
    private String model;

    @Value("${agnes.max-tokens:4096}")
    private int maxTokens;

    @Value("${agnes.temperature:0.7}")
    private double temperature;

    public AgnesAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "agnes";
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
        return "your-agnes-api-key";
    }
}
