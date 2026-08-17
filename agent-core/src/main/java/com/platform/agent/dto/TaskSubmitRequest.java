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

    /** RAG 知识库增强：知识库 ID（可选，不传不增强） */
    private String datasetId;

    /** RAG 检索条数（可选，默认 3，上限 10） */
    private Integer ragTopK;

    /** 所属项目（阶段 1，可选；传了则校验项目写权限并随任务落库） */
    private String projectId;

    /** 输入资产（图生图/图生视频的来源，可选） */
    private String sourceAssetId;

    /** 批次号（批量任务同组，可选，前端批量生成时生成） */
    private String batchGroupId;
}
