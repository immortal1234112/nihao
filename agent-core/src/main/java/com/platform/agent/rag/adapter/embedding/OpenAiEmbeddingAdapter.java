package com.platform.agent.rag.adapter.embedding;

import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI Embedding 适配器（text-embedding-3-small）。
 * 配置示例（Nacos / model_config 表，provider=openai-embedding）:
 *   openai-embedding:
 *     api-key: sk-xxx
 *     base-url: https://api.openai.com
 *     model: text-embedding-3-small
 */
@Component
public class OpenAiEmbeddingAdapter extends AbstractOpenAiEmbeddingAdapter {

    @Value("${openai-embedding.api-key:}")
    private String apiKey;

    @Value("${openai-embedding.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${openai-embedding.model:text-embedding-3-small}")
    private String model;

    @Value("${openai-embedding.dimension:1536}")
    private int dimension;

    public OpenAiEmbeddingAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "openai-embedding";
    }

    @Override
    protected String embeddingPath() {
        return "/v1/embeddings";
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
