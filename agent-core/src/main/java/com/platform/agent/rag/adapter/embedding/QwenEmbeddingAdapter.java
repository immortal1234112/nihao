package com.platform.agent.rag.adapter.embedding;

import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通义千问 Embedding 适配器（DashScope compatible-mode，text-embedding-v3）。
 * 配置示例（Nacos / model_config 表，provider=qwen-embedding）:
 *   qwen-embedding:
 *     api-key: sk-xxx          # 可与 qwen 共用同一 DashScope key
 *     base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *     model: text-embedding-v3
 */
@Component
public class QwenEmbeddingAdapter extends AbstractOpenAiEmbeddingAdapter {

    @Value("${qwen-embedding.api-key:}")
    private String apiKey;

    @Value("${qwen-embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${qwen-embedding.model:text-embedding-v3}")
    private String model;

    @Value("${qwen-embedding.dimension:1024}")
    private int dimension;

    public QwenEmbeddingAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "qwen-embedding";
    }

    @Override
    protected String embeddingPath() {
        return "/embeddings";
    }

    @Override
    protected String getConfiguredApiKey() {
        return apiKey;
    }

    @Override
    protected String getConfiguredBaseUrl() {
        return baseUrl;
    }

    @Override
    protected String getConfiguredModel() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
