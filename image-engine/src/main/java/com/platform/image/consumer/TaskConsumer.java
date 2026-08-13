package com.platform.image.consumer;

import com.platform.engine.consumer.AbstractTaskConsumer;
import com.platform.image.service.ImageGenerationFacade;
import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.TaskMessage;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 图片任务消费者 —— 同步模式（消费线程内执行，失败按 x-death 重试 ≤3 次进 DLQ）。
 * 幂等、锁与重试逻辑见 {@link AbstractTaskConsumer}。
 */
@Component
public class TaskConsumer extends AbstractTaskConsumer {

    private final ImageGenerationFacade facade;

    public TaskConsumer(StringRedisTemplate stringRedisTemplate, ImageGenerationFacade facade) {
        super(stringRedisTemplate, null);
        this.facade = facade;
    }

    @RabbitListener(queues = QueueConstants.IMAGE_TASK_QUEUE, concurrency = "3-10")
    public void onMessage(TaskMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        handle(message, channel, deliveryTag, xDeath);
    }

    @Override
    protected String lockPrefix() {
        return "task:lock:";
    }

    @Override
    protected long lockTtlSeconds() {
        return 3600;
    }

    @Override
    protected boolean asyncProcessing() {
        return false;
    }

    @Override
    protected void processTask(TaskMessage message) {
        facade.generate(message.taskId(), message.userId(), message.tenantId(), message.params(), message.stepOrder());
    }
}
