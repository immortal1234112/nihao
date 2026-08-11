package com.platform.agent.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 检索请求。
 */
@Data
public class RagSearchRequest {

    @NotBlank(message = "datasetId 不能为空")
    private String datasetId;

    @NotBlank(message = "query 不能为空")
    private String query;

    /** 返回条数，默认 3，上限 10 */
    private Integer topK;
}
