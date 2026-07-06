package com.platform.mq.config;

import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.exchange.QueueConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ========== Exchanges ==========

    @Bean
    public TopicExchange taskExchange() {
        return new TopicExchange(ExchangeConstants.TASK_EXCHANGE);
    }

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(ExchangeConstants.AUDIT_EXCHANGE);
    }

    @Bean
    public TopicExchange notifyExchange() {
        return new TopicExchange(ExchangeConstants.NOTIFY_EXCHANGE);
    }

    @Bean
    public TopicExchange taskDlxExchange() {
        return new TopicExchange(ExchangeConstants.TASK_DLX_EXCHANGE);
    }

    // ========== Queues (with dead letter) ==========

    @Bean
    public Queue imageTaskQueue() {
        return QueueBuilder.durable(QueueConstants.IMAGE_TASK_QUEUE)
                .deadLetterExchange(ExchangeConstants.TASK_DLX_EXCHANGE)
                .deadLetterRoutingKey("task.dead.image")
                .build();
    }

    @Bean
    public Queue videoTaskQueue() {
        return QueueBuilder.durable(QueueConstants.VIDEO_TASK_QUEUE)
                .deadLetterExchange(ExchangeConstants.TASK_DLX_EXCHANGE)
                .deadLetterRoutingKey("task.dead.video")
                .build();
    }

    @Bean
    public Queue taskResultQueue() {
        return QueueBuilder.durable(QueueConstants.TASK_RESULT_QUEUE).build();
    }

    @Bean
    public Queue auditLogQueue() {
        return QueueBuilder.durable(QueueConstants.AUDIT_LOG_QUEUE).build();
    }

    @Bean
    public Queue taskDeadLetterQueue() {
        return QueueBuilder.durable(QueueConstants.TASK_DLQ).build();
    }

    // ========== Bindings ==========

    @Bean
    public Binding imageTaskBinding() {
        return BindingBuilder.bind(imageTaskQueue()).to(taskExchange()).with("task.image.*");
    }

    @Bean
    public Binding videoTaskBinding() {
        return BindingBuilder.bind(videoTaskQueue()).to(taskExchange()).with("task.video.*");
    }

    @Bean
    public Binding taskResultBinding() {
        return BindingBuilder.bind(taskResultQueue()).to(taskExchange()).with("task.result.#");
    }

    @Bean
    public Binding auditLogBinding() {
        return BindingBuilder.bind(auditLogQueue()).to(auditExchange()).with("audit.log.#");
    }

    @Bean
    public Binding taskDlqBinding() {
        return BindingBuilder.bind(taskDeadLetterQueue()).to(taskDlxExchange()).with("task.dead.#");
    }
}
