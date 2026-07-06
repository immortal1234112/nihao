package com.platform.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoGenerationResult {
    private String videoUrl;
    private String thumbnailUrl;
    private String format;
    private int width;
    private int height;
    private int duration;
    private int fps;
    private long fileSize;
}
