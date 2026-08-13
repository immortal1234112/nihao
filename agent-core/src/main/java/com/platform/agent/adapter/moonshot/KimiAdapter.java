package com.platform.agent.adapter.moonshot;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kimi (Moonshot) 大模型适配器。
 * Kimi API 兼容 OpenAI 格式，endpoint: https://api.moonshot.cn/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   moonshot:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://api.moonshot.cn
 *     model: moonshot-v1-8k
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Component
public class KimiAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${moonshot.api-key:}")
    private String apiKey;

    @Value("${moonshot.base-url:https://api.moonshot.cn}")
    private String baseUrl;

    @Value("${moonshot.model:moonshot-v1-8k}")
    private String model;

    @Value("${moonshot.max-tokens:4096}")
    private int maxTokens;

    @Value("${moonshot.temperature:0.7}")
    private double temperature;

    public KimiAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "moonshot";
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
        return "your-moonshot-api-key";
    }
}
