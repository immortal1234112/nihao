package com.platform.engine.consumer;

import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskMessage;
import com.platform.mq.message.TaskResultMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 引擎任务消费者抽象基类 —— 收敛 image/video 两引擎消费逻辑。
 * <p>
 * 两种处理模式：
 * <ul>
 *   <li>同步（image-engine）：消费线程内执行，失败按 RabbitMQ x-death 计数重试（≤3 次后进 DLQ）</li>
 *   <li>异步（video-engine）：提交执行器成功后才 ACK（适配器长轮询最长 600s，不占消费线程）</li>
 * </ul>
 * Redis 幂等锁（task:lock:*）防重复消息导致的重复生成；异步模式提交执行器成功后才 ACK，
 * 提交失败（执行器拒绝）重新入队并按 x-death 封顶后发 FAILED 回执，避免消息丢失/任务卡 PROCESSING。
 * <p>
 * 子类职责：声明 @RabbitListener（各自队列常量，方法体内委托 handle()）+
 * 实现 lockPrefix / lockTtlSeconds / asyncProcessing / processTask 钩子。
 */
@Slf4j
public abstract class AbstractTaskConsumer {

    protected final StringRedisTemplate stringRedisTemplate;

    /** 异步模式执行器；同步模式传 null */
    private final ThreadPoolTaskExecutor asyncExecutor;

    /** 结果回执发送（同步模式传 null；异步模式提交失败时补发 FAILED 回执） */
    private final RabbitTemplate rabbitTemplate;

    protected AbstractTaskConsumer(StringRedisTemplate stringRedisTemplate, ThreadPoolTaskExecutor asyncExecutor,
                                   RabbitTemplate rabbitTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.asyncExecutor = asyncExecutor;
        this.rabbitTemplate = rabbitTemplate;
    }

    // ==================== 子类钩子 ====================

    /** 幂等锁 key 前缀，如 "task:lock:" */
    protected abstract String lockPrefix();

    /** 幂等锁 TTL（秒） */
    protected abstract long lockTtlSeconds();

    /** 是否异步处理（异步 = 提交执行器后 ACK + 执行器；同步 = x-death 重试） */
    protected abstract boolean asyncProcessing();

    /** 执行任务（子类调 facade.generate；勿在此 catch —— 基类统一处理） */
    protected abstract void processTask(TaskMessage message);

    // ==================== 模板入口 ====================

    /** 子类 @RabbitListener 方法委托调用 */
    protected void handle(TaskMessage message, Channel channel, long deliveryTag, List<Map<String, Object>> xDeath) {
        if (asyncProcessing()) {
            handleAsync(message, channel, deliveryTag, xDeath);
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

    /** 异步模式：提交执行器成功后才 ACK，长轮询/生成在独立执行器中（不占 RabbitMQ 消费线程） */
    private void handleAsync(TaskMessage message, Channel channel, long deliveryTag, List<Map<String, Object>> xDeath) {
        String taskId = message.taskId();
        try {
            // 关键顺序：先提交执行器，成功返回后才 ACK。
            // 原实现先 ACK 后提交，ACK 后提交失败/进程崩溃会丢消息且无恢复机制 → 任务永久卡 PROCESSING。
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
            channel.basicAck(deliveryTag, false);
        } catch (RejectedExecutionException e) {
            // 执行器已 shutdown（服务关闭中）：任务从未运行 → 重新入队；
            // 按 x-death 封顶（≤3 次）后发 FAILED 回执并弃投进 DLQ，避免无限重投
            log.warn("Task {} executor rejected (service shutting down?): {}", taskId, e.getMessage());
            if (getXDeathCount(xDeath) < 3) {
                try { channel.basicNack(deliveryTag, false, true); } catch (Exception ignored) {}
            } else {
                sendFailedReceipt(message, "executor rejected after retries");
                try { channel.basicNack(deliveryTag, false, false); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            // 其他异常（如 CallerRunsPolicy 内联执行时 finally 异常冒出、channel 异常）：
            // 任务可能已执行 → 补发 FAILED 回执（重复回执由 agent-core 按 retry_count 封顶对账）并 ACK
            log.error("Task {} submit to executor failed: {}", taskId, e.getMessage());
            sendFailedReceipt(message, e.getMessage());
            try { channel.basicAck(deliveryTag, false); } catch (Exception ignored) {}
        }
    }

    // ==================== 内部方法 ====================

    /** 发送 FAILED 回执（发送形态与 AbstractGenerationFacade.reportFailed 一致）；同步模式 rabbitTemplate 为 null 时跳过 */
    private void sendFailedReceipt(TaskMessage message, String error) {
        if (rabbitTemplate == null) return;
        try {
            TaskResultMessage receipt = new TaskResultMessage(
                    message.taskId(), message.engineType(), "FAILED", null,
                    error, 0L, message.stepOrder());
            rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, "task.result.failed", receipt);
        } catch (Exception e) {
            log.error("Failed to send FAILED receipt for task {}: {}", message.taskId(), e.getMessage());
        }
    }

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
