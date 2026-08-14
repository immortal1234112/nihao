package com.platform.agent.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.entity.WorkflowStep;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.mapper.WorkflowStepMapper;
import com.platform.agent.service.WorkflowEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡死任务恢复扫描 —— 任务可靠性兜底（引擎崩溃 / MQ 消息丢失后任务永久停滞）。
 *
 * <p>两条恢复路径：
 * <ol>
 *   <li>PROCESSING 超时（默认 2h，与引擎幂等锁 TTL 7200s 对齐）：引擎崩溃后任务卡在处理中
 *       → 终态化 FAILED，挂起（PROCESSING/PENDING）步骤全部 SKIPPED。
 *       CAS 条件更新（仅当仍为 PROCESSING）防覆盖并发完成的回执。</li>
 *   <li>PENDING 超时（默认 30min）：任务创建后从未收到回执（首次/后续分发消息丢失）
 *       → 重新分发。advanceStep 内部 dispatchStep 为 CAS（PENDING→PROCESSING），天然幂等，
 *       不会重复分发/重复扣费；若再次失败，下一轮扫描继续重试。</li>
 * </ol>
 *
 * <p>阈值均为保守上界（正常图片秒级、视频分钟级完成），可经 environment 配置调整；
 * 处理时长超过阈值的超长任务会被判定为卡死，需调大 processing-timeout-minutes。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckTaskRecoveryJob {

    private static final String RECOVERY_TIMEOUT_MSG = "任务执行超时，已由恢复扫描终止";

    private final AgentTaskMapper agentTaskMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final WorkflowEngine workflowEngine;

    /** 任务卡 PROCESSING 超过该时长判定为卡死（分钟；默认 120 = 视频引擎幂等锁 TTL 7200s） */
    @Value("${platform.recovery.processing-timeout-minutes:120}")
    private long processingTimeoutMinutes;

    /** 任务卡 PENDING 超过该时长判定为分发丢失（分钟） */
    @Value("${platform.recovery.pending-timeout-minutes:30}")
    private long pendingTimeoutMinutes;

    @Scheduled(fixedDelayString = "${platform.recovery.interval-ms:300000}", initialDelay = 60000)
    public void recoverStuckTasks() {
        try {
            recoverStuckProcessing();
        } catch (Exception e) {
            log.error("Recovery scan (PROCESSING) failed", e);
        }
        try {
            recoverStuckPending();
        } catch (Exception e) {
            log.error("Recovery scan (PENDING) failed", e);
        }
    }

    /** 引擎崩溃兜底：PROCESSING 超时 → FAILED，挂起步骤 SKIPPED */
    private void recoverStuckProcessing() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(processingTimeoutMinutes);
        List<AgentTask> stuck = agentTaskMapper.selectList(
                new LambdaQueryWrapper<AgentTask>()
                        .eq(AgentTask::getStatus, AgentTask.STATUS_PROCESSING)
                        .lt(AgentTask::getUpdatedAt, deadline));
        for (AgentTask task : stuck) {
            // CAS：仅当仍为 PROCESSING 才终态化，防止覆盖并发完成的回执
            int updated = agentTaskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                    .eq(AgentTask::getId, task.getId())
                    .eq(AgentTask::getStatus, AgentTask.STATUS_PROCESSING)
                    .set(AgentTask::getStatus, AgentTask.STATUS_FAILED)
                    .set(AgentTask::getErrorMsg, RECOVERY_TIMEOUT_MSG)
                    .set(AgentTask::getCompletedAt, LocalDateTime.now()));
            if (updated > 0) {
                workflowStepMapper.update(null, new LambdaUpdateWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, task.getId())
                        .in(WorkflowStep::getStatus,
                                WorkflowStep.STATUS_PROCESSING, WorkflowStep.STATUS_PENDING)
                        .set(WorkflowStep::getStatus, WorkflowStep.STATUS_SKIPPED));
                log.warn("Recovery: task {} stuck in PROCESSING > {}min, marked FAILED",
                        task.getId(), processingTimeoutMinutes);
            }
        }
    }

    /** 消息丢失兜底：PENDING 超时 → 重新分发（advanceStep 幂等） */
    private void recoverStuckPending() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(pendingTimeoutMinutes);
        List<AgentTask> stuck = agentTaskMapper.selectList(
                new LambdaQueryWrapper<AgentTask>()
                        .eq(AgentTask::getStatus, AgentTask.STATUS_PENDING)
                        .lt(AgentTask::getUpdatedAt, deadline));
        for (AgentTask task : stuck) {
            // CAS + 续期：仅当仍为 PENDING 才重新分发；续期避免同轮内被并发重复触发
            int renewed = agentTaskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                    .eq(AgentTask::getId, task.getId())
                    .eq(AgentTask::getStatus, AgentTask.STATUS_PENDING)
                    .set(AgentTask::getUpdatedAt, LocalDateTime.now()));
            if (renewed > 0) {
                try {
                    workflowEngine.advanceStep(task.getId());
                    log.warn("Recovery: task {} stuck in PENDING > {}min, redispatched",
                            task.getId(), pendingTimeoutMinutes);
                } catch (Exception e) {
                    log.error("Recovery redispatch failed (next scan will retry): taskId={}", task.getId(), e);
                }
            }
        }
    }
}
