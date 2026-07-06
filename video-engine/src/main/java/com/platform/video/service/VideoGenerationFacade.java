package com.platform.video.service;

import com.platform.feign.StorageFeignClient;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskResultMessage;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final VideoPostProcessor postProcessor;
    private final RabbitTemplate rabbitTemplate;
    private final StorageFeignClient storageFeignClient;

    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            VideoGenerateRequest request = buildRequest(params);
            VideoGenerationAdapter adapter = modelRegistry.select(request);
            log.info("Selected video model: {} for task: {}", adapter.modelName(), taskId);

            // 文生视频 or 图生视频
            VideoGenerationResult result;
            if (request.getSourceImageUrl() != null && !request.getSourceImageUrl().isEmpty()) {
                result = adapter.imageToVideo(request);
            } else {
                result = adapter.textToVideo(request);
            }

            result = postProcessor.process(result);

            List<TaskResultMessage.FileInfo> fileInfos = new ArrayList<>();
            fileInfos.add(new TaskResultMessage.FileInfo(
                    null, result.getVideoUrl(), result.getThumbnailUrl()));

            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "DONE", fileInfos, null, duration);
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
            log.info("Video task {} completed in {}ms", taskId, duration);

        } catch (Exception e) {
            log.error("Video task {} failed: {}", taskId, e.getMessage());
            long duration = System.currentTimeMillis() - start;
            TaskResultMessage message = new TaskResultMessage(
                    taskId, "VIDEO", "FAILED", null, e.getMessage(), duration);
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", message);
        }
    }

    private VideoGenerateRequest buildRequest(Map<String, Object> params) {
        return VideoGenerateRequest.builder()
                .prompt((String) params.get("prompt"))
                .duration(params.containsKey("duration") ? ((Number) params.get("duration")).intValue() : 5)
                .fps(params.containsKey("fps") ? ((Number) params.get("fps")).intValue() : 24)
                .resolution((String) params.getOrDefault("resolution", "1080p"))
                .style((String) params.get("style"))
                .sourceImageUrl((String) params.get("sourceImageUrl"))
                .negativePrompt((String) params.get("negativePrompt"))
                .build();
    }
}
