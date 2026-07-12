package com.platform.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 任务提交请求。
 */
@Data
public class TaskSubmitRequest {

    @NotBlank(message = "prompt 不能为空")
    private String prompt;

    /** 工作流类型：SINGLE / PIPELINE / BATCH */
    private String workflowType = "SINGLE";

    /** 引擎类型：IMAGE / VIDEO */
    private String engineType = "IMAGE";

    /** 指定模型 provider（如 qwen-image, dalle, stability） */
    private String modelProvider;

    /** 图片参数 */
    private String size;
    private Integer count = 1;
    private String style;
    private String format;
    private String quality;
    private String negativePrompt;
    private String aspectRatio;

    /** 视频参数 */
    private Integer duration;
    private Integer fps;
    private String resolution;

    /** 批量参数 */
    private Integer batchCount;

    /** 流水线步骤定义 */
    private java.util.List<Map<String, Object>> pipelineSteps;
}
