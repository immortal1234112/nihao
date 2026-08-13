package com.platform.video.consumer;

import com.platform.engine.consumer.AbstractTaskConsumer;
import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.TaskMessage;
import com.platform.video.service.VideoGenerationFacade;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 视频任务消费者 —— 异步模式（立即 ACK + 异步执行器，长轮询不占消费线程）。
 * 幂等锁与降级回执见 {@link AbstractTaskConsumer}。
 */
@Component
public class TaskConsumer extends AbstractTaskConsumer {

    private final VideoGenerationFacade facade;

    public TaskConsumer(StringRedisTemplate stringRedisTemplate, ThreadPoolTaskExecutor videoTaskExecutor, VideoGenerationFacade facade) {
        super(stringRedisTemplate, videoTaskExecutor);
        this.facade = facade;
    }

    @RabbitListener(queues = QueueConstants.VIDEO_TASK_QUEUE, concurrency = "1-3")
    public void onMessage(TaskMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        handle(message, channel, deliveryTag, xDeath);
    }

    @Override
    protected String lockPrefix() {
        return "task:lock:video:";
    }

    @Override
    protected long lockTtlSeconds() {
        return 7200;
    }

    @Override
    protected boolean asyncProcessing() {
        return true;
    }

    @Override
    protected void processTask(TaskMessage message) {
        facade.generate(message.taskId(), message.userId(), message.tenantId(), message.params(), message.stepOrder());
    }
}
