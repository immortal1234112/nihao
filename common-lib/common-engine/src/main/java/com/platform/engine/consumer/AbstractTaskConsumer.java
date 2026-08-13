package com.platform.engine.consumer;

import com.platform.mq.message.TaskMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 引擎任务消费者抽象基类 —— 收敛 image/video 两引擎消费逻辑。
 * <p>
 * 两种处理模式：
 * <ul>
 *   <li>同步（image-engine）：消费线程内执行，失败按 RabbitMQ x-death 计数重试（≤3 次后进 DLQ）</li>
 *   <li>异步（video-engine）：立即 ACK + 提交异步执行器（适配器长轮询最长 600s，不占消费线程）</li>
 * </ul>
 * Redis 幂等锁（task:lock:*）防重复消息导致的重复生成；异步模式下立即 ACK 后
 * MQ 不再重投，任务级重试由 agent-core 的 retry_count 机制（FAILED 回执后重新分发）兜底。
 * <p>
 * 子类职责：声明 @RabbitListener（各自队列常量，方法体内委托 handle()）+
 * 实现 lockPrefix / lockTtlSeconds / asyncProcessing / processTask 钩子。
 */
@Slf4j
public abstract class AbstractTaskConsumer {

    protected final StringRedisTemplate stringRedisTemplate;

    /** 异步模式执行器；同步模式传 null */
    private final ThreadPoolTaskExecutor asyncExecutor;

    protected AbstractTaskConsumer(StringRedisTemplate stringRedisTemplate, ThreadPoolTaskExecutor asyncExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.asyncExecutor = asyncExecutor;
    }

    // ==================== 子类钩子 ====================

    /** 幂等锁 key 前缀，如 "task:lock:" */
    protected abstract String lockPrefix();

    /** 幂等锁 TTL（秒） */
    protected abstract long lockTtlSeconds();

    /** 是否异步处理（异步 = 立即 ACK + 执行器；同步 = x-death 重试） */
    protected abstract boolean asyncProcessing();

    /** 执行任务（子类调 facade.generate；勿在此 catch —— 基类统一处理） */
    protected abstract void processTask(TaskMessage message);

    // ==================== 模板入口 ====================

    /** 子类 @RabbitListener 方法委托调用 */
    protected void handle(TaskMessage message, Channel channel, long deliveryTag, List<Map<String, Object>> xDeath) {
        if (asyncProcessing()) {
            handleAsync(message, channel, deliveryTag);
        } else {
            handleSync(message, channel, deliveryTag, xDeath);
        }
    }

    /** 同步模式：消费线程内执行，失败按 x-death 重试（≤3 次后进 DLQ） */
    private void handleSync(TaskMessage message, Channel channel, long deliveryTag, List<Map<String, Object>> xDeath) {
        String taskId = message.taskId();
        // 锁 key 必须含 stepOrder：BATCH 任务 4 个步骤 4 条消息共用 taskId，
        // 若只按 taskId 加锁，先到步骤占锁后其余步骤消息会被误判为重复而 ACK 丢弃 → 任务永远卡 PROCESSING
        String lockKey = lockPrefix() + taskId + ":" + message.stepOrder();
        try {
            // 幂等性：Redis 分布式锁
            Boolean acquired = acquireLock(lockKey);
            if (Boolean.FALSE.equals(acquired)) {
                log.info("Task {} already processed (idempotent), ACK", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("Processing task: {}, workflowType: {}, step: {}", taskId, message.workflowType(), message.stepOrder());
            processTask(message);

            channel.basicAck(deliveryTag, false);
            // 释放锁：facade 内部失败时会发 result.failed 而不会抛异常（走此成功路径），
            // 若不删锁，agent-core 的重试消息会被幂等分支丢弃 → 任务永远卡 PROCESSING。
            // 顺序必须 ACK 在先：若删锁后 ACK 前崩溃，消息会重投导致重复生成（付费渠道多扣费）。
            releaseLock(lockKey, taskId);
            log.info("Task {} processed and ACKed", taskId);

        } catch (Exception e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            try {
                // 重试计数用 RabbitMQ x-death（消息已重投次数）——避免无限 requeue 死循环
                if (getXDeathCount(xDeath) < 3) {
                    channel.basicNack(deliveryTag, false, true);
                    releaseLock(lockKey, taskId);   // 释放锁以允许重试
                } else {
                    channel.basicNack(deliveryTag, false, false);
                    log.warn("Task {} exceeded max retries, sent to DLQ", taskId);
                }
            } catch (Exception ackEx) {
                log.error("Failed to nack task {}: {}", taskId, ackEx.getMessage());
            }
        }
    }

    /** 异步模式：立即 ACK，长轮询/生成在独立执行器中（不占 RabbitMQ 消费线程） */
    private void handleAsync(TaskMessage message, Channel channel, long deliveryTag) {
        String taskId = message.taskId();
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("Task {} basicAck failed: {}", taskId, e.getMessage());
        }
        try {
            asyncExecutor.execute(() -> {
                // 锁 key 含 stepOrder，理由同 handleSync：BATCH 各步骤独立加锁并行处理
                String lockKey = lockPrefix() + taskId + ":" + message.stepOrder();
                Boolean acquired = null;
                try {
                    acquired = acquireLock(lockKey);
                    if (Boolean.FALSE.equals(acquired)) {
                        log.info("Task {} already processed, skip", taskId);
                        return;
                    }
                    log.info("Processing task: {}, step: {}", taskId, message.stepOrder());
                    processTask(message);
                } catch (Exception e) {
                    // facade 内部 catch-all 会发 FAILED 回执；此处兜底（如 Redis 异常等）
                    log.error("Task {} async processing failed: {}", taskId, e.getMessage());
                } finally {
                    // 只在本次成功拿到锁时释放；acquire 失败说明其他实例在跑，绝不能删锁
                    if (Boolean.TRUE.equals(acquired)) {
                        releaseLock(lockKey, taskId);
                    }
                }
            });
        } catch (Exception e) {
            // 执行器极端异常（如队列满且拒绝策略失败）：直接报 FAILED 回执，避免任务卡 PROCESSING
            log.error("Task {} submit to executor failed: {}", taskId, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private Boolean acquireLock(String lockKey) {
        return stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(lockTtlSeconds()));
    }

    private void releaseLock(String lockKey, String taskId) {
        Boolean deleted = stringRedisTemplate.delete(lockKey);
        if (Boolean.FALSE.equals(deleted)) {
            log.warn("Task {} lock release failed (TTL will expire)", taskId);
        }
    }

    /** RabbitMQ x-death 计数（消息被拒绝重投的次数），无则返回 0 */
    private int getXDeathCount(List<Map<String, Object>> xDeath) {
        if (xDeath == null || xDeath.isEmpty()) return 0;
        for (Map<String, Object> entry : xDeath) {
            if (entry.get("count") instanceof Number n) {
                return n.intValue();
            }
        }
        return 0;
    }
}
