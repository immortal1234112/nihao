package com.platform.agent.adapter.zhipu;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 智谱 GLM 大模型适配器。
 * 智谱 API 兼容 OpenAI 格式，endpoint: https://open.bigmodel.cn/api/paas/v4/chat/completions
 * （base-url 已含 /api/paas/v4 前缀，覆写 chatCompletionsPath 为空前段）
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   zhipu:
 *     api-key: xxxxxxxxxxxxxxxx
 *     base-url: https://open.bigmodel.cn/api/paas/v4
 *     model: glm-4-plus
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Component
public class GlmAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${zhipu.api-key:}")
    private String apiKey;

    @Value("${zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${zhipu.model:glm-4-plus}")
    private String model;

    @Value("${zhipu.max-tokens:4096}")
    private int maxTokens;

    @Value("${zhipu.temperature:0.7}")
    private double temperature;

    public GlmAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "zhipu";
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
    protected String chatCompletionsPath() {
        return "/chat/completions";
    }

    @Override
    protected String placeholderApiKey() {
        return "your-zhipu-api-key";
    }
}
