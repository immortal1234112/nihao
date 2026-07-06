package com.platform.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoModelCapability {
    private int maxDuration;
    private List<String> supportedResolutions;
    private List<Integer> supportedFps;
    private int maxBatchSize;
}
