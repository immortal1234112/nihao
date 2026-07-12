package com.platform.video.service;

import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoModelCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ModelRegistry {

    private final List<VideoGenerationAdapter> adapters;

    public ModelRegistry(List<VideoGenerationAdapter> adapters) {
        this.adapters = adapters;
        log.info("Video ModelRegistry initialized with {} adapter(s)", adapters.size());
    }

    public VideoGenerationAdapter select(VideoGenerateRequest request) {
        List<VideoGenerationAdapter> available = getAvailableAdapters();
        if (available.isEmpty()) {
            throw new RuntimeException("No available video generation model");
        }

        // 1. 按指定 provider 精确匹配
        if (request.getModelProvider() != null && !request.getModelProvider().isBlank()) {
            for (VideoGenerationAdapter a : available) {
                String provider = getProviderFromAdapter(a);
                if (request.getModelProvider().equalsIgnoreCase(a.modelName())
                        || request.getModelProvider().equalsIgnoreCase(provider)) {
                    return a;
                }
            }
            log.warn("Requested video provider '{}' not available, falling back", request.getModelProvider());
        }

        // 2. 按 style / resolution 匹配
        if (request.getStyle() != null) {
            for (VideoGenerationAdapter a : available) {
                if (a.capability().getSupportedResolutions() != null
                        && a.capability().getSupportedResolutions().contains(request.getResolution())) {
                    return a;
                }
            }
        }
        return available.get(0);
    }

    private String getProviderFromAdapter(VideoGenerationAdapter adapter) {
        String className = adapter.getClass().getSimpleName().toLowerCase();
        if (className.contains("runway")) return "runway";
        if (className.contains("kling")) return "kling";
        if (className.contains("pika")) return "pika";
        if (className.contains("luma")) return "luma";
        if (className.contains("deepseek")) return "deepseek";
        if (className.contains("cogvideo")) return "cogvideo";
        if (className.contains("wan")) return "wan";
        return className;
    }

    public List<VideoGenerationAdapter> getAvailableAdapters() {
        return adapters.stream().filter(VideoGenerationAdapter::healthCheck).toList();
    }

    public List<String> getModelNames() {
        return adapters.stream().map(VideoGenerationAdapter::modelName).toList();
    }
}
