package com.platform.agent.rag.adapter.embedding;

import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 硅基流动 Embedding 适配器（SiliconFlow，OpenAI 兼容 /v1/embeddings，BAAI/bge-m3 免费）。
 * 官网: https://siliconflow.cn（控制台申请免费 API Key）
 * 配置示例（Nacos / model_config 表，provider=siliconflow-embedding）:
 *   siliconflow-embedding:
 *     api-key: sk-xxx
 *     base-url: https://api.siliconflow.cn/v1
 *     model: BAAI/bge-m3
 */
@Component
public class SiliconFlowEmbeddingAdapter extends AbstractOpenAiEmbeddingAdapter {

    @Value("${siliconflow-embedding.api-key:}")
    private String apiKey;

    @Value("${siliconflow-embedding.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${siliconflow-embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${siliconflow-embedding.dimension:1024}")
    private int dimension;

    public SiliconFlowEmbeddingAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "siliconflow-embedding";
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
