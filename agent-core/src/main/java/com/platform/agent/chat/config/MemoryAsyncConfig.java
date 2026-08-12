package com.platform.agent.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 记忆提取异步线程池。
 * 注意：@Async 线程不携带 TenantContext（ThreadLocal 不跨线程），
 * 提取处理器必须通过方法显式传参（userId/tenantId）。
 * 不复用 ragExecutor：RAG 文档解析是重批次任务，记忆提取是高频小任务，混用会互相阻塞。
 */
@Configuration
public class MemoryAsyncConfig {

    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("memory-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
