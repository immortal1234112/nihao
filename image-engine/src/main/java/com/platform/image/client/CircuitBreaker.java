package com.platform.image.client;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器 —— 每个模型单例。
 * 状态: CLOSED → OPEN → HALF_OPEN → CLOSED
 */
@Slf4j
public class CircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    /** 最近 N 次请求 */
    private static final int WINDOW_SIZE = 10;
    /** 失败率阈值 */
    private static final double FAILURE_THRESHOLD = 0.5;
    /** OPEN 持续时长（秒） */
    private static final long OPEN_DURATION_SEC = 30;
    /** HALF_OPEN 探测请求数 */
    private static final int PROBE_COUNT = 3;

    private final String modelName;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger probeSuccessCount = new AtomicInteger(0);
    private final AtomicInteger probeTotalCount = new AtomicInteger(0);
    private volatile Instant openedAt;

    public CircuitBreaker(String modelName) {
        this.modelName = modelName;
    }

    /**
     * 检查是否允许请求通过。
     */
    public synchronized boolean allowRequest() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (Instant.now().isAfter(openedAt.plusSeconds(OPEN_DURATION_SEC))) {
                    state.set(State.HALF_OPEN);
                    probeSuccessCount.set(0);
                    probeTotalCount.set(0);
                    log.info("Circuit breaker HALF_OPEN for model: {}", modelName);
                    return true;
                }
                return false;
            }
            case HALF_OPEN -> {
                return probeTotalCount.get() < PROBE_COUNT;
            }
        }
        return false;
    }

    /**
     * 记录成功。
     */
    public synchronized void recordSuccess() {
        State currentState = state.get();
        switch (currentState) {
            case CLOSED -> {
                totalCount.incrementAndGet();
                if (totalCount.get() >= WINDOW_SIZE) {
                    resetWindow();
                }
            }
            case HALF_OPEN -> {
                probeSuccessCount.incrementAndGet();
                probeTotalCount.incrementAndGet();
                if (probeSuccessCount.get() >= PROBE_COUNT) {
                    state.set(State.CLOSED);
                    resetWindow();
                    log.info("Circuit breaker CLOSED for model: {}", modelName);
                }
            }
        }
    }

    /**
     * 记录失败。
     */
    public synchronized void recordFailure() {
        State currentState = state.get();
        switch (currentState) {
            case CLOSED -> {
                failureCount.incrementAndGet();
                totalCount.incrementAndGet();
                if (totalCount.get() >= WINDOW_SIZE) {
                    double rate = (double) failureCount.get() / totalCount.get();
                    if (rate >= FAILURE_THRESHOLD) {
                        state.set(State.OPEN);
                        openedAt = Instant.now();
                        log.warn("Circuit breaker OPEN for model: {} (failure rate: {})", modelName, String.format("%.0f%%", rate * 100));
                    }
                    resetWindow();
                }
            }
            case HALF_OPEN -> {
                probeTotalCount.incrementAndGet();
                state.set(State.OPEN);
                openedAt = Instant.now();
                log.warn("Circuit breaker OPEN again for model: {} (probe failed)", modelName);
            }
        }
    }

    public State getState() {
        return state.get();
    }

    private void resetWindow() {
        failureCount.set(0);
        totalCount.set(0);
    }
}
