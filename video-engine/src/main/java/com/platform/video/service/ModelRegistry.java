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

    public List<VideoGenerationAdapter> getAvailableAdapters() {
        return adapters.stream().filter(VideoGenerationAdapter::healthCheck).toList();
    }

    public List<String> getModelNames() {
        return adapters.stream().map(VideoGenerationAdapter::modelName).toList();
    }
}
