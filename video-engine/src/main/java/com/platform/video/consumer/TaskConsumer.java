package com.platform.video.consumer;

import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.TaskMessage;
import com.platform.video.service.VideoGenerationFacade;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 视频任务消费者 —— 预取数=1（视频资源密集，串行消费）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConsumer {

    private final VideoGenerationFacade facade;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "task:lock:video:";

    @RabbitListener(queues = QueueConstants.VIDEO_TASK_QUEUE, concurrency = "1-3")
    public void onMessage(TaskMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        String taskId = message.taskId();
        String lockKey = LOCK_PREFIX + taskId;

        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofHours(2));
            if (Boolean.FALSE.equals(acquired)) {
                log.info("Video task {} already processed, ACK", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("Processing video task: {}, step: {}", taskId, message.stepOrder());
            // stepOrder 回填回执，agent-core 按 taskId+stepOrder 精确对账
            facade.generate(taskId, message.userId(), message.tenantId(), message.params(), message.stepOrder());

            channel.basicAck(deliveryTag, false);
            // 释放锁：facade 内部失败时会发 result.failed 而不会抛异常（走此成功路径），
            // 若不删锁，agent-core 的重试消息会被幂等分支丢弃 → 任务永远卡 PROCESSING。
            // 顺序必须 ACK 在先：若删锁后 ACK 前崩溃，消息会重投导致重复生成。
            Boolean deleted = stringRedisTemplate.delete(lockKey);
            if (Boolean.FALSE.equals(deleted)) {
                log.warn("Video task {} lock release failed (TTL will expire)", taskId);
            }
            log.info("Video task {} processed and ACKed", taskId);

        } catch (Exception e) {
            log.error("Video task {} failed: {}", taskId, e.getMessage());
            try {
                // 重试计数用 RabbitMQ x-death（原实现读 message.retryCount() 恒为 0 → 无限 requeue）
                if (getXDeathCount(xDeath) < 3) {
                    channel.basicNack(deliveryTag, false, true);
                    stringRedisTemplate.delete(lockKey);
                } else {
                    channel.basicNack(deliveryTag, false, false);
                    log.warn("Video task {} exceeded max retries, sent to DLQ", taskId);
                }
            } catch (Exception ignored) {}
        }
    }

    /** RabbitMQ x-death 计数（消息被拒绝重投的次数），无则返回 0 */
    private int getXDeathCount(List<Map<String, Object>> xDeath) {
        if (xDeath == null || xDeath.isEmpty()) return 0;
        for (Map<String, Object> entry : xDeath) {
            if (entry.get("count") instanceof Number n) {
                return n.intValue();
            }
        }
        return 0;
    }
}
