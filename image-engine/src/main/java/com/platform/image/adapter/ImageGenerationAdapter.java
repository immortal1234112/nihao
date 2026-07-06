package com.platform.image.adapter;

import com.platform.image.model.GenerateRequest;
import com.platform.image.model.GenerationResult;
import com.platform.image.model.ModelCapability;
import com.platform.image.model.TaskStatus;

import java.util.List;

/**
 * 图片生成模型适配器接口。
 * 所有接入的图片生成模型（DALL·E、Stable Diffusion、Midjourney 等）实现此接口。
 */
public interface ImageGenerationAdapter {

    /** 模型名称 */
    String modelName();

    /** 模型能力描述 */
    ModelCapability capability();

    /** 同步生成图片 */
    List<GenerationResult> generate(GenerateRequest request);

    /** 查询异步任务状态 */
    TaskStatus queryStatus(String externalTaskId);

    /** 健康检查 */
    boolean healthCheck();
}
