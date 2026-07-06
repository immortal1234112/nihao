package com.platform.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoGenerateRequest {
    private String prompt;
    private int duration;
    private int fps;
    private String resolution;
    private String style;
    private String sourceImageUrl;
    private String negativePrompt;
}
