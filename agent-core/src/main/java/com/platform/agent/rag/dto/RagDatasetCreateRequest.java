package com.platform.agent.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建知识库请求。
 */
@Data
public class RagDatasetCreateRequest {

    @NotBlank(message = "name 不能为空")
    private String name;

    private String description;

    /** embedding 提供商，默认 qwen-embedding（创建后不可换） */
    private String embeddingProvider;

    /** embedding 模型覆盖（留空用 provider 默认模型） */
    private String embeddingModel;
}
