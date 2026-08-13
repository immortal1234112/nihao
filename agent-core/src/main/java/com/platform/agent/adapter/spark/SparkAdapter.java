package com.platform.agent.adapter.spark;

import com.platform.agent.adapter.AbstractOpenAiCompatAdapter;
import com.platform.core.config.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 讯飞星火 Spark 大模型适配器。
 * Spark API 兼容 OpenAI 格式，endpoint: https://spark-api-open.xf-yun.com/v1/chat/completions
 * 差异点（已覆写）：Authorization 为 Bearer {apiKey}:{apiSecret}；不支持 temperature/max_tokens 参数。
 *
 * 配置示例（Nacos / bootstrap.yml）:
 *   spark:
 *     app-id: xxxxxxxxxxxxxxxx
 *     api-key: xxxxxxxxxxxxxxxx
 *     api-secret: xxxxxxxxxxxxxxxx
 *     model: 4.0Ultra
 */
@Component
public class SparkAdapter extends AbstractOpenAiCompatAdapter {

    private static final String BASE_URL = "https://spark-api-open.xf-yun.com";

    @Value("${spark.app-id:}")
    private String appId;

    @Value("${spark.api-key:}")
    private String apiKey;

    @Value("${spark.api-secret:}")
    private String apiSecret;

    @Value("${spark.model:4.0Ultra}")
    private String model;

    public SparkAdapter(ModelConfigService configService) {
        super(configService);
    }

    @Override
    public String provider() {
        return "spark";
    }

    @Override
    protected String apiKey() {
        return apiKey;
    }

    @Override
    protected String apiSecret() {
        return apiSecret;
    }

    @Override
    protected String baseUrl() {
        return BASE_URL;
    }

    @Override
    protected String model() {
        return model;
    }

    @Override
    protected int maxTokens() {
        return 4096;
    }

    @Override
    protected double temperature() {
        return 0.7;
    }

    /** Spark 使用 Bearer {apiKey}:{apiSecret} 作为 Authorization */
    @Override
    protected String authHeader(String effectiveApiKey, String effectiveApiSecret) {
        return "Bearer " + effectiveApiKey + ":" + (effectiveApiSecret != null ? effectiveApiSecret : "");
    }

    /** Spark 请求体不支持 temperature/max_tokens */
    @Override
    protected boolean supportsTemperatureParams() {
        return false;
    }

    /** Spark 需 appId + apiKey + apiSecret 三项齐全 */
    @Override
    public boolean healthCheck() {
        if (!requireApiKey) return true;  // dev mode: always available
        if (configService.isEnabled(provider())) return true;
        return appId != null && !appId.isEmpty()
                && apiKey != null && !apiKey.isEmpty()
                && apiSecret != null && !apiSecret.isEmpty();
    }
}
