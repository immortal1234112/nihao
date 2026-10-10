package com.platform.agent.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 流式对话线程池与心跳调度器。
 * <p>
 * 不复用 ragExecutor / memoryExecutor：一次生成会占住一个线程数十秒，
 * 与 RAG 解析（重批次）、记忆提取（高频小任务）混用会互相阻塞。
 * <p>
 * 注意：@Async 与自定义线程池同样不携带 TenantContext（ThreadLocal 不跨线程），
 * 流式服务必须由 controller 线程取出 userId/tenantId 后显式传参。
 */
@Configuration
public class ChatStreamAsyncConfig {

    @Bean("chatStreamExecutor")
    public Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-stream-");
        // 关机不等长连接跑完（最多 180s），否则停服务会被拖住
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * 心跳调度器：每条流一个定时任务，每 15s 发一条 SSE 注释帧。
     * 2 个线程足够（任务只是往 emitter 写几字节，且 ResponseBodyEmitter.send 是同步的）。
     */
    @Bean("chatHeartbeatScheduler")
    public ScheduledExecutorService chatHeartbeatScheduler() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newScheduledThreadPool(2,
                r -> new Thread(r, "chat-hb-" + seq.incrementAndGet()));
    }
}
