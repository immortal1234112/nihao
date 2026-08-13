package com.platform.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 视频任务异步执行器配置。
 * <p>
 * 视频适配器长轮询（本地推理 / 云端 API 排队）最长可达 600s，若在 RabbitMQ
 * 消费线程内同步执行会占死 listener 线程；消费者改为立即 ACK 后提交到本执行器。
 * 队列满时 CallerRunsPolicy 兜底（退回调用线程执行），保证任务不丢失。
 */
@Configuration
public class AsyncTaskConfig {

    @Bean("videoTaskExecutor")
    public ThreadPoolTaskExecutor videoTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("video-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
