package com.platform.image.service;

import com.platform.image.adapter.ImageGenerationAdapter;
import com.platform.image.model.GenerateRequest;
import com.platform.image.model.ModelCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册中心 —— 管理所有 ImageGenerationAdapter 实现。
 */
@Slf4j
@Component
public class ModelRegistry {

    private final List<ImageGenerationAdapter> adapters;
    private final Map<String, com.platform.image.client.CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public ModelRegistry(List<ImageGenerationAdapter> adapters) {
        this.adapters = adapters;
        log.info("ModelRegistry initialized with {} adapter(s)", adapters.size());
        for (ImageGenerationAdapter adapter : adapters) {
            log.info("  - {} (available: {})", adapter.modelName(), adapter.healthCheck());
        }
    }

    /**
     * 根据请求特征匹配最优模型。
     */
    public ImageGenerationAdapter select(GenerateRequest request) {
        List<ImageGenerationAdapter> available = getAvailableAdapters();
        if (available.isEmpty()) {
            throw new RuntimeException("No available image generation model");
        }

        // 按 style 匹配
        if (request.getStyle() != null) {
            for (ImageGenerationAdapter adapter : available) {
                ModelCapability cap = adapter.capability();
                if (cap.getSupportedStyles() != null && cap.getSupportedStyles().contains(request.getStyle())) {
                    return adapter;
                }
            }
        }

        // 默认返回第一个可用的
        return available.get(0);
    }

    /**
     * 获取所有可用的适配器（健康检查 + 熔断器判断）。
     */
    public List<ImageGenerationAdapter> getAvailableAdapters() {
        return adapters.stream()
                .filter(a -> {
                    var cb = circuitBreakers.computeIfAbsent(a.modelName(),
                            k -> new com.platform.image.client.CircuitBreaker(a.modelName()));
                    return a.healthCheck() && cb.allowRequest();
                })
                .toList();
    }

    /**
     * 获取所有已注册的模型名称。
     */
    public List<String> getModelNames() {
        return adapters.stream().map(ImageGenerationAdapter::modelName).toList();
    }
}
