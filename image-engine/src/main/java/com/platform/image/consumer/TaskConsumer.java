package com.platform.image.consumer;

import com.platform.image.service.ImageGenerationFacade;
import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.TaskMessage;
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
 * 图片任务消费者 —— 从 RabbitMQ 消费图片生成任务，幂等执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskConsumer {

    private final ImageGenerationFacade facade;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "task:lock:";

    @RabbitListener(queues = QueueConstants.IMAGE_TASK_QUEUE, concurrency = "3-10")
    public void onMessage(TaskMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        String taskId = message.taskId();
        String lockKey = LOCK_PREFIX + taskId;

        try {
            // 幂等性：Redis 分布式锁
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofHours(1));
            if (Boolean.FALSE.equals(acquired)) {
                log.info("Task {} already processed (idempotent), ACK", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("Processing image task: {}, workflowType: {}, step: {}", taskId, message.workflowType(), message.stepOrder());

            // 执行生成（stepOrder 回填回执，agent-core 按 taskId+stepOrder 精确对账）
            facade.generate(taskId, message.userId(), message.tenantId(), message.params(), message.stepOrder());

            // 手动 ACK
            channel.basicAck(deliveryTag, false);
            // 释放锁：facade 内部失败时会发 result.failed 而不会抛异常（走此成功路径），
            // 若不删锁，agent-core 的重试消息会被幂等分支丢弃 → 任务永远卡 PROCESSING。
            // 顺序必须 ACK 在先：若删锁后 ACK 前崩溃，消息会重投导致重复生成（付费渠道多扣费）。
            Boolean deleted = stringRedisTemplate.delete(lockKey);
            if (Boolean.FALSE.equals(deleted)) {
                log.warn("Task {} lock release failed (TTL will expire)", taskId);
            }
            log.info("Task {} processed and ACKed", taskId);

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            try {
                // 重试计数用 RabbitMQ x-death（消息已重投次数）——原实现读 message.retryCount() 恒为 0，
                // 一旦 catch 触发就是无限 requeue 死循环
                if (getXDeathCount(xDeath) < 3) {
                    // 重新入队重试
                    channel.basicNack(deliveryTag, false, true);
                    // 释放锁以允许重试
                    stringRedisTemplate.delete(lockKey);
                } else {
                    // 超过重试次数 → 拒绝且不重新入队 → 进入 DLQ
                    channel.basicNack(deliveryTag, false, false);
                    log.warn("Task {} exceeded max retries, sent to DLQ", taskId);
                }
            } catch (Exception ackEx) {
                log.error("Failed to nack task {}: {}", taskId, ackEx.getMessage());
            }
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
