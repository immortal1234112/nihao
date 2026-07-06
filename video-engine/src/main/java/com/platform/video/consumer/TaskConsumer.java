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
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
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

            log.info("Processing video task: {}", taskId);
            facade.generate(taskId, message.userId(), message.tenantId(), message.params());

            channel.basicAck(deliveryTag, false);
            log.info("Video task {} processed and ACKed", taskId);

        } catch (Exception e) {
            log.error("Video task {} failed: {}", taskId, e.getMessage());
            try {
                if (message.retryCount() < 3) {
                    channel.basicNack(deliveryTag, false, true);
                    stringRedisTemplate.delete(lockKey);
                } else {
                    channel.basicNack(deliveryTag, false, false);
                    log.warn("Video task {} sent to DLQ", taskId);
                }
            } catch (Exception ignored) {}
        }
    }
}
