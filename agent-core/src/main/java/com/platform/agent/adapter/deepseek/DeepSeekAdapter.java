package com.platform.agent.adapter.deepseek;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 大模型适配器。
 * DeepSeek API 兼容 OpenAI 格式，endpoint: https://api.deepseek.com/v1/chat/completions
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   deepseek:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://api.deepseek.com
 *     model: deepseek-flash
 *     max-tokens: 4096
 *     temperature: 0.7
 *
 * 模型名说明（2026-10 官方文档）：主推 deepseek-flash（由 V4.1-Flash 提供）
 * 与 deepseek-v4-pro；deepseek-chat 是遗留名，仍被接受但已不在文档主推列表。
 * 实际生效值优先取 model_config 表，这里的默认值只作为兜底。
 */
@Component
public class DeepSeekAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-flash}")
    private String model;

    @Value("${deepseek.max-tokens:4096}")
    private int maxTokens;

    @Value("${deepseek.temperature:0.7}")
    private double temperature;

    public DeepSeekAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "deepseek";
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
        return "your-deepseek-api-key";
    }
}
