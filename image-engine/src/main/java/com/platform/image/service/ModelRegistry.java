package com.platform.image.service;

import com.platform.engine.registry.AbstractModelRegistry;
import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.client.CircuitBreaker;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.ModelCapability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心 —— 管理所有 ImageGenerationAdapter 实现（匹配/可用性逻辑见 {@link AbstractModelRegistry}）。
 * 图片侧额外启用熔断器（按模型名），失败快速降级到其他可用模型。
 */
@Component
public class ModelRegistry extends AbstractModelRegistry<ImageGenerationAdapter> {

    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public ModelRegistry(List<ImageGenerationAdapter> adapters) {
        super(adapters, "Image");
    }

    /**
     * 根据请求特征匹配最优模型（provider 精确匹配 → style 匹配 → 兜底第一个可用）。
     */
    public ImageGenerationAdapter select(GenerateRequest request) {
        return select(request.getModelProvider(), request.getStyle());
    }

    @Override
    protected String modelName(ImageGenerationAdapter adapter) {
        return adapter.modelName();
    }

    @Override
    protected boolean healthCheck(ImageGenerationAdapter adapter) {
        return adapter.healthCheck();
    }

    @Override
    protected boolean isAvailable(ImageGenerationAdapter adapter) {
        var cb = circuitBreakers.computeIfAbsent(adapter.modelName(), CircuitBreaker::new);
        return adapter.healthCheck() && cb.allowRequest();
    }

    @Override
    protected String providerFromAdapter(ImageGenerationAdapter adapter) {
        String className = adapter.getClass().getSimpleName().toLowerCase();
        if (className.contains("qwen")) return "qwen-image";
        if (className.contains("dalle")) return "dalle";
        if (className.contains("sd")) return "stability";
        if (className.contains("midjourney")) return "midjourney";
        if (className.contains("ernie")) return "ernie-image";
        if (className.contains("agnes")) return "agnes-image";
        return className;
    }

    @Override
    protected boolean capabilityMatches(ImageGenerationAdapter adapter, String style) {
        ModelCapability cap = adapter.capability();
        return cap.getSupportedStyles() != null && cap.getSupportedStyles().contains(style);
    }
}
