package com.platform.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生成结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationResult {
    private String url;
    private String base64Data;
    private String format;
    private int width;
    private int height;
    private long seed;
}
