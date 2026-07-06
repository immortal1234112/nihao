package com.platform.video.service;

import com.platform.video.model.VideoGenerationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 视频后处理器 —— 抽帧缩略图、格式转码等。
 * 视频抽帧需要 FFmpeg/JCodec，当前为预留桩实现。
 */
@Slf4j
@Component
public class VideoPostProcessor {

    /**
     * 后处理视频结果。
     */
    public VideoGenerationResult process(VideoGenerationResult result) {
        log.debug("Video post-process: {} (stub — no processing)", result.getVideoUrl());
        // TODO: 集成 FFmpeg 进行格式转码、分辨率调整

        // 生成缩略图 URL（预留：应抽第一帧）
        if (result.getThumbnailUrl() == null) {
            result.setThumbnailUrl(result.getVideoUrl() + "?thumb=1");
        }
        return result;
    }
}
