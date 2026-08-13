package com.platform.video.service;

import com.platform.engine.registry.AbstractModelRegistry;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型注册中心 —— 管理所有 VideoGenerationAdapter 实现（匹配/可用性逻辑见 {@link AbstractModelRegistry}）。
 * 视频侧按 resolution 匹配。
 */
@Component
public class ModelRegistry extends AbstractModelRegistry<VideoGenerationAdapter> {

    public ModelRegistry(List<VideoGenerationAdapter> adapters) {
        super(adapters, "Video");
    }

    /**
     * 根据请求特征匹配最优模型（provider 精确匹配 → resolution 匹配 → 兜底第一个可用）。
     */
    public VideoGenerationAdapter select(VideoGenerateRequest request) {
        return select(request.getModelProvider(), request.getResolution());
    }

    @Override
    protected String modelName(VideoGenerationAdapter adapter) {
        return adapter.modelName();
    }

    @Override
    protected boolean healthCheck(VideoGenerationAdapter adapter) {
        return adapter.healthCheck();
    }

    @Override
    protected String providerFromAdapter(VideoGenerationAdapter adapter) {
        String className = adapter.getClass().getSimpleName().toLowerCase();
        if (className.contains("runway")) return "runway";
        if (className.contains("kling")) return "kling";
        if (className.contains("pika")) return "pika";
        if (className.contains("luma")) return "luma";
        if (className.contains("deepseek")) return "deepseek";
        if (className.contains("cogvideo")) return "cogvideo";
        if (className.contains("wan")) return "wan";
        if (className.contains("agnes")) return "agnes-video";
        return className;
    }

    @Override
    protected boolean capabilityMatches(VideoGenerationAdapter adapter, String resolution) {
        return adapter.capability().getSupportedResolutions() != null
                && adapter.capability().getSupportedResolutions().contains(resolution);
    }
}
