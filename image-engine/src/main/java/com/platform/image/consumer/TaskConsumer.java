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
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
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

            log.info("Processing image task: {}, workflowType: {}", taskId, message.workflowType());

            // 执行生成
            facade.generate(taskId, message.userId(), message.tenantId(), message.params());

            // 手动 ACK
            channel.basicAck(deliveryTag, false);
            log.info("Task {} processed and ACKed", taskId);

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            try {
                // 判断重试次数
                int retryCount = message.retryCount();
                if (retryCount < 3) {
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
}
