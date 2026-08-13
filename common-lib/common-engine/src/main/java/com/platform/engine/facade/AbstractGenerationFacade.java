package com.platform.engine.facade;

import com.platform.feign.StorageFeignClient;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 引擎生成门面抽象基类 —— 收敛结果回执发送与 Storage 上传辅助。
 * <p>
 * image/video 两引擎各自保留生成流程（适配器选择/调用、结果后处理），
 * 回执构造/发送、Feign 上传、MultipartFile 构建等重复逻辑在此统一。
 */
@Slf4j
public abstract class AbstractGenerationFacade {

    protected final RabbitTemplate rabbitTemplate;
    protected final StorageFeignClient storageFeignClient;

    protected AbstractGenerationFacade(RabbitTemplate rabbitTemplate, StorageFeignClient storageFeignClient) {
        this.rabbitTemplate = rabbitTemplate;
        this.storageFeignClient = storageFeignClient;
    }

    // ==================== 结果回执 ====================

    /** 发送成功回执（stepOrder 供 agent-core 按 taskId+stepOrder 精确对账） */
    protected void reportDone(String taskId, String engineType, List<TaskResultMessage.FileInfo> fileInfos, long duration, int stepOrder) {
        TaskResultMessage message = new TaskResultMessage(taskId, engineType, "DONE", fileInfos, null, duration, stepOrder);
        rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.done", message);
        log.info("{} task {} step {} completed in {}ms", engineType, taskId, stepOrder, duration);
    }

    /** 发送失败回执 */
    protected void reportFailed(String taskId, String engineType, String error, long duration, int stepOrder) {
        log.error("{} task {} failed: {}", engineType, taskId, error);
        TaskResultMessage message = new TaskResultMessage(taskId, engineType, "FAILED", null, error, duration, stepOrder);
        rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", message);
    }

    // ==================== Storage 上传辅助 ====================

    /**
     * 上传字节内容到 Storage（走带身份头的 Feign 内部接口），返回 FileInfo。
     * 失败返回 null（由调用方决定降级策略：回退 provider 直链）。
     */
    protected TaskResultMessage.FileInfo uploadToStorage(String taskId, String userId, String tenantId,
                                                         byte[] bytes, String filename, String contentType) {
        try {
            MultipartFile file = createMultipartFile(bytes, filename, contentType);
            var uploadResult = storageFeignClient.upload(file,
                    userId != null ? userId : "system",
                    tenantId != null ? tenantId : "system");
            if (uploadResult != null && uploadResult.getData() != null) {
                String fileId = uploadResult.getData().fileId();
                log.info("File uploaded: taskId={}, fileId={}", taskId, fileId);
                return new TaskResultMessage.FileInfo(fileId, uploadResult.getData().url(), null);
            }
            log.warn("Storage upload returned empty result: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("Failed to upload to storage: {}", e.getMessage());
        }
        return null;
    }

    /** 构建内存 MultipartFile（供 Feign 上传） */
    protected MultipartFile createMultipartFile(byte[] bytes, String filename, String contentType) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return filename; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return bytes.length == 0; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            @Override public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
                java.nio.file.Files.write(dest.toPath(), bytes);
            }
        };
    }

    /** 请求参数 int 读取辅助（Number 兼容） */
    protected int getInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }
}
