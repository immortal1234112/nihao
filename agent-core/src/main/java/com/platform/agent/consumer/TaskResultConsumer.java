package com.platform.agent.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.entity.WorkflowStep;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.mapper.WorkflowStepMapper;
import com.platform.agent.service.WorkflowEngine;
import com.platform.core.util.JsonUtils;
import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.TaskResultMessage;
import com.rabbitmq.client.Channel;
import java.util.ArrayList;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务结果消费者 —— 消费 Engine 完成后的回调消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskResultConsumer {

    private final WorkflowStepMapper workflowStepMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final WorkflowEngine workflowEngine;

    @RabbitListener(queues = QueueConstants.TASK_RESULT_QUEUE, concurrency = "3-5")
    public void onMessage(TaskResultMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        try {
            log.info("Task result received: taskId={}, status={}, duration={}ms",
                    message.taskId(), message.status(), message.durationMs());

            // 1. 更新对应步骤
            updateStep(message);

            // 2. 更新任务状态
            updateTask(message);

            // 3. 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("Failed to process task result: taskId={}", message.taskId(), e);
            try {
                // 重试计数用 x-death（消息已重投次数），≤3 次 requeue，否则弃投进结果死信队列
                // （原实现无限 requeue 且结果队列无死信，毒消息会占死全部消费线程）
                if (getXDeathCount(xDeath) < 3) {
                    channel.basicNack(deliveryTag, false, true);
                } else {
                    channel.basicNack(deliveryTag, false, false);
                    log.warn("Task result {} exceeded max retries, sent to DLQ", message.taskId());
                }
            } catch (Exception ignored) {}
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

    private void updateStep(TaskResultMessage message) {
        WorkflowStep step = resolveStep(message);
        if (step == null) {
            log.warn("No matching step for result: taskId={}, stepOrder={}, engineType={}",
                    message.taskId(), message.stepOrder(), message.engineType());
            return;
        }

        if ("DONE".equals(message.status())) {
            step.setStatus(WorkflowStep.STATUS_DONE);
            step.setOutput(JsonUtils.toJson(message));
            step.setFinishedAt(LocalDateTime.now());
            workflowStepMapper.updateById(step);
            // 检查是否还有 PENDING 步骤，有则推进
            workflowEngine.advanceStep(message.taskId());
        } else if ("FAILED".equals(message.status())) {
            // 重试 / 终失败均由 handleStepFailure 闭环（内部 advanceStep 或置任务 FAILED）。
            // 不再 return 跳过 updateTask —— 原实现导致终失败后任务永远卡 PROCESSING。
            workflowEngine.handleStepFailure(message.taskId(), step.getStepOrder(), message.errorInfo());
        }
    }

    /**
     * 优先按 taskId+stepOrder 精确匹配回执目标步骤；
     * 无 stepOrder 的积压旧消息回退到 engineType 匹配的 PROCESSING 步骤（只取第一个，避免 BATCH 错配）。
     */
    private WorkflowStep resolveStep(TaskResultMessage message) {
        if (message.stepOrder() != null) {
            List<WorkflowStep> exact = workflowStepMapper.selectList(
                    new LambdaQueryWrapper<WorkflowStep>()
                            .eq(WorkflowStep::getTaskId, message.taskId())
                            .eq(WorkflowStep::getStepOrder, message.stepOrder())
                            .last("LIMIT 1"));
            if (!exact.isEmpty()) return exact.get(0);
        }
        List<WorkflowStep> processingSteps = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, message.taskId())
                        .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PROCESSING)
                        .orderByAsc(WorkflowStep::getStepOrder));
        return processingSteps.stream()
                .filter(s -> message.engineType() == null || message.engineType().equals(s.getStepType()))
                .findFirst()
                .orElse(null);
    }

    private void updateTask(TaskResultMessage message) {
        AgentTask task = agentTaskMapper.selectById(message.taskId());
        if (task == null) return;
        // 已取消的任务忽略一切回执，防止取消后仍被复活为 DONE/FAILED
        if (AgentTask.STATUS_CANCELLED.equals(task.getStatus())) {
            log.info("Task cancelled, ignore result: taskId={}", task.getId());
            return;
        }

        // 检查所有步骤是否完成
        List<WorkflowStep> allSteps = workflowStepMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, message.taskId())
        );

        boolean allDone = allSteps.stream().allMatch(s ->
                WorkflowStep.STATUS_DONE.equals(s.getStatus())
                        || WorkflowStep.STATUS_SKIPPED.equals(s.getStatus()));
        boolean anyFailed = allSteps.stream().anyMatch(s ->
                WorkflowStep.STATUS_FAILED.equals(s.getStatus()));

        if (anyFailed) {
            task.setStatus(AgentTask.STATUS_FAILED);
            task.setErrorMsg(message.errorInfo());
            task.setCompletedAt(LocalDateTime.now());
        } else if (allDone) {
            task.setStatus(AgentTask.STATUS_DONE);
            // BATCH/PIPELINE 多步骤任务聚合所有步骤输出，避免后到回执覆盖先到结果（仅剩最后一步文件）
            task.setResult(buildMergedResult(task, allSteps));
            task.setCompletedAt(LocalDateTime.now());
        } else {
            task.setStatus(AgentTask.STATUS_PROCESSING);
        }

        agentTaskMapper.updateById(task);
        log.info("Task updated: id={}, status={}", task.getId(), task.getStatus());
    }

    /** 聚合所有 DONE 步骤的 outputFiles 为任务级 result（SINGLE 单步骤时与原行为等价） */
    @SuppressWarnings("unchecked")
    private String buildMergedResult(AgentTask task, List<WorkflowStep> allSteps) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("status", AgentTask.STATUS_DONE);
        merged.put("taskId", task.getId());
        List<Object> files = new ArrayList<>();
        for (WorkflowStep s : allSteps) {
            if (!WorkflowStep.STATUS_DONE.equals(s.getStatus()) || s.getOutput() == null) continue;
            try {
                Map<String, Object> o = JsonUtils.fromJson(s.getOutput(), Map.class);
                Object of = o.get("outputFiles");
                if (of instanceof List<?> list) {
                    for (Object f : list) {
                        if (f != null) files.add(f);
                    }
                }
            } catch (Exception ignore) {
                // 单步骤输出解析失败不阻断任务完成
            }
        }
        merged.put("outputFiles", files);
        return JsonUtils.toJson(merged);
    }
}
