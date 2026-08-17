package com.platform.agent.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 项目挂载知识库请求。
 */
@Data
public class DatasetBindRequest {

    @NotBlank(message = "datasetId 不能为空")
    private String datasetId;
}
