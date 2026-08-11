package com.platform.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 图片生成请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest {
    private String prompt;
    private String modelProvider;
    private String size;
    private String aspectRatio;
    private int count;
    private String style;
    private String format;
    private String quality;
    private String negativePrompt;
    private List<String> referenceImages;
}
