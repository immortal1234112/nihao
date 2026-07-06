package com.platform.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模型能力声明。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelCapability {
    private List<String> supportedResolutions;
    private List<String> supportedStyles;
    private List<String> supportedFormats;
    private int maxBatchSize;
}
