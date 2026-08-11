package com.platform.agent.rag.dto;

import lombok.Data;

/**
 * 更新知识库请求（仅名称/描述）。
 */
@Data
public class RagDatasetUpdateRequest {

    private String name;

    private String description;
}
