package com.platform.agent.adapter.qwen;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通义千问 Qwen 大模型适配器。
 * 通过阿里云 DashScope OpenAI 兼容模式调用:
 * https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * （base-url 已含 /compatible-mode/v1 前缀，覆写 chatCompletionsPath 为空前段）
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   qwen:
 *     api-key: sk-xxxxxxxxxxxxxxxx
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *     model: qwen-turbo
 *     max-tokens: 4096
 *     temperature: 0.7
 */
@Component
public class QwenTextAdapter extends AbstractOpenAiCompatAdapter {

    @Value("${qwen.api-key:}")
    private String apiKey;

    @Value("${qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${qwen.model:qwen-turbo}")
    private String model;

    @Value("${qwen.max-tokens:4096}")
    private int maxTokens;

    @Value("${qwen.temperature:0.7}")
    private double temperature;

    public QwenTextAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "qwen";
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
        return "your-qwen-api-key";
    }
}
