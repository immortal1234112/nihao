package com.platform.video.adapter;

import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import com.platform.video.model.VideoModelCapability;
import com.platform.video.model.VideoTaskStatus;

/**
 * 视频生成模型适配器接口。
 */
public interface VideoGenerationAdapter {

    String modelName();

    VideoModelCapability capability();

    VideoGenerationResult textToVideo(VideoGenerateRequest request);

    VideoGenerationResult imageToVideo(VideoGenerateRequest request);

    VideoTaskStatus queryStatus(String externalTaskId);

    boolean healthCheck();
}
