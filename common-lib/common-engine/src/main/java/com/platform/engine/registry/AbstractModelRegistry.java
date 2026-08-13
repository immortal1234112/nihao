package com.platform.engine.registry;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 引擎模型注册中心抽象基类 —— 收敛「按 provider / capability 匹配 + 可用性过滤 + 模型清单」公共逻辑。
 * <p>
 * image/video 两引擎通过泛型 + 钩子方法复用：子类提供适配器类型、provider 映射与
 * capability 匹配（image 按 style、video 按 resolution）；熔断等差异化逻辑可覆写 isAvailable。
 */
@Slf4j
public abstract class AbstractModelRegistry<A> {

    protected final List<A> adapters;

    protected AbstractModelRegistry(List<A> adapters, String engineName) {
        this.adapters = adapters;
        log.info("{} ModelRegistry initialized with {} adapter(s)", engineName, adapters.size());
        for (A adapter : adapters) {
            log.info("  - {} (available: {})", modelName(adapter), healthCheck(adapter));
        }
    }

    // ==================== 子类钩子 ====================

    protected abstract String modelName(A adapter);

    /** 健康检查（供日志与默认可用性判断） */
    protected abstract boolean healthCheck(A adapter);

    /** 可用性判断（默认仅健康检查；image 覆写加入熔断器） */
    protected boolean isAvailable(A adapter) {
        return healthCheck(adapter);
    }

    /** 由实现类名推断 provider 键（如 QwenImageAdapter → "qwen-image"） */
    protected abstract String providerFromAdapter(A adapter);

    /** capability 匹配：image 按 supportedStyles、video 按 supportedResolutions */
    protected abstract boolean capabilityMatches(A adapter, String key);

    // ==================== 公共逻辑 ====================

    /**
     * 按指定 provider 精确匹配（modelName 或 provider 键），
     * 否则按 capability（style/resolution）匹配，兜底返回第一个可用适配器。
     */
    protected A select(String modelProvider, String capabilityKey) {
        List<A> available = getAvailableAdapters();
        if (available.isEmpty()) {
            throw new RuntimeException("No available generation model");
        }

        // 1. 按指定 provider 精确匹配
        if (modelProvider != null && !modelProvider.isBlank()) {
            for (A adapter : available) {
                if (modelProvider.equalsIgnoreCase(modelName(adapter))
                        || modelProvider.equalsIgnoreCase(providerFromAdapter(adapter))) {
                    return adapter;
                }
            }
            log.warn("Requested provider '{}' not available, falling back", modelProvider);
        }

        // 2. 按 capability（style/resolution）匹配
        if (capabilityKey != null) {
            for (A adapter : available) {
                if (capabilityMatches(adapter, capabilityKey)) {
                    return adapter;
                }
            }
        }

        // 3. 默认返回第一个可用
        return available.get(0);
    }

    /** 获取所有可用适配器（健康检查 + 可选熔断） */
    public List<A> getAvailableAdapters() {
        return adapters.stream().filter(this::isAvailable).toList();
    }

    /** 获取所有已注册的模型名称 */
    public List<String> getModelNames() {
        return adapters.stream().map(this::modelName).toList();
    }
}
