package com.platform.video.service;

import com.platform.engine.facade.AbstractGenerationFacade;
import com.platform.feign.StorageFeignClient;
import com.platform.mq.message.TaskResultMessage;
import com.platform.video.adapter.VideoGenerationAdapter;
import com.platform.video.model.VideoGenerateRequest;
import com.platform.video.model.VideoGenerationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 视频生成门面 —— 编排模型选择、API 调用、后处理、结果上报。
 * 回执发送与 Storage 上传辅助见 {@link AbstractGenerationFacade}。
 */
@Service
@Slf4j
public class VideoGenerationFacade extends AbstractGenerationFacade {

    private final ModelRegistry modelRegistry;
    private final VideoPostProcessor postProcessor;

    public VideoGenerationFacade(ModelRegistry modelRegistry, VideoPostProcessor postProcessor,
                                 RabbitTemplate rabbitTemplate, StorageFeignClient storageFeignClient) {
        super(rabbitTemplate, storageFeignClient);
        this.modelRegistry = modelRegistry;
        this.postProcessor = postProcessor;
    }

    /**
     * 执行视频生成并上报结果。
     *
     * @param stepOrder 对应 agent-core 的步骤序号，回执时回填以便精确对账（BATCH 多步骤）
     */
    public void generate(String taskId, String userId, String tenantId, Map<String, Object> params, int stepOrder) {
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

            // 处理文本脚本输出（DeepSeek 等）：上传到 Storage 获得可下载 URL
            List<TaskResultMessage.FileInfo> fileInfos = new ArrayList<>();
            if (result.getScriptContent() != null && !result.getScriptContent().isEmpty()) {
                TaskResultMessage.FileInfo fi = uploadScript(taskId, userId, tenantId, result.getScriptContent());
                if (fi != null) {
                    fileInfos.add(fi);
                }
            }
            if (result.getVideoUrl() != null && !result.getVideoUrl().startsWith("deepseek-chat://")) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }
            if (fileInfos.isEmpty()) {
                fileInfos.add(new TaskResultMessage.FileInfo(
                        null, result.getVideoUrl(), result.getThumbnailUrl()));
            }

            long duration = System.currentTimeMillis() - start;
            reportDone(taskId, "VIDEO", fileInfos, duration, stepOrder);

        } catch (Exception e) {
            reportFailed(taskId, "VIDEO", e.getMessage(), System.currentTimeMillis() - start, stepOrder);
        }
    }

    private VideoGenerateRequest buildRequest(Map<String, Object> params) {
        return VideoGenerateRequest.builder()
                .prompt((String) params.get("prompt"))
                .duration(getInt(params, "duration", 5))
                .fps(getInt(params, "fps", 24))
                .resolution((String) params.getOrDefault("resolution", "1080p"))
                .style((String) params.get("style"))
                .sourceImageUrl((String) params.get("sourceImageUrl"))
                .negativePrompt((String) params.get("negativePrompt"))
                .modelProvider((String) params.get("modelProvider"))
                .build();
    }

    /**
     * 将脚本文本上传到 Storage Service（走带身份头的 Feign 内部接口，返回可下载 URL）。
     */
    private TaskResultMessage.FileInfo uploadScript(String taskId, String userId, String tenantId, String script) {
        return uploadToStorage(taskId, userId, tenantId,
                script.getBytes(StandardCharsets.UTF_8),
                "deepseek-script-" + taskId + ".txt",
                "text/plain; charset=UTF-8");
    }
}
