package com.platform.image.client;

import lombok.extern.slf4j.Slf4j;

/**
 * 重试策略 —— 指数退避，不同状态码区别处理。
 */
@Slf4j
public class RetryPolicy {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 1000;

    /**
     * 判断是否应该重试。
     */
    public boolean shouldRetry(int attempt, int statusCode) {
        if (attempt >= MAX_RETRIES) return false;

        // timeout: 重试
        if (statusCode == 0 || statusCode == -1) return true;

        // 429: 退避后重试
        if (statusCode == 429) return attempt < 3;

        // 5xx: 最多 2 次
        if (statusCode >= 500 && statusCode < 600) return attempt < 2;

        // 4xx: 不重试
        return false;
    }

    /**
     * 判断是否应该重试（基于异常）。
     */
    public boolean shouldRetry(int attempt, Exception e) {
        if (attempt >= MAX_RETRIES) return false;

        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // 连接超时 / 读取超时 → 重试
        if (msg.contains("timeout") || msg.contains("timed out")) return true;

        // 连接被拒绝 → 重试
        if (msg.contains("connection refused")) return true;

        // IO 异常 → 重试
        if (e instanceof java.io.IOException) return attempt < 2;

        return false;
    }

    /**
     * 重试前等待（指数退避）。
     */
    public void sleepBeforeRetry(int attempt) {
        long delay = BASE_DELAY_MS * (1L << attempt); // 1s, 2s, 4s
        log.debug("Retry attempt {}, sleeping {}ms", attempt + 1, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
