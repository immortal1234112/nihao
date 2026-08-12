package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.dto.TaskSubmitRequest;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.entity.WorkflowStep;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.mapper.WorkflowStepMapper;
import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.service.RagService;
import com.platform.core.base.PageResult;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.core.util.JsonUtils;
import com.platform.feign.SecurityFeignClient;
import com.platform.feign.UserFeignClient;
import com.platform.feign.dto.AuditResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务服务 —— 任务的创建、查询、取消。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AgentTaskMapper agentTaskMapper;
    private final WorkflowStepMapper workflowStepMapper;
    private final WorkflowEngine workflowEngine;
    private final IntentParser intentParser;
    private final UserFeignClient userFeignClient;
    private final SecurityFeignClient securityFeignClient;
    private final RagService ragService;

    // ==================== 提交任务 ====================

    @Transactional
    public AgentTask submitTask(String userId, String tenantId, TaskSubmitRequest request) {
        // 1. 意图解析
        Map<String, String> intent = intentParser.parse(request.getPrompt());

        // 2. Prompt 审核（通过 Feign）—— 先审核后扣配额：审核失败不产生扣费
        try {
            var auditResult = securityFeignClient.auditPrompt(Map.of("prompt", request.getPrompt()));
            if (auditResult != null && auditResult.getData() != null && !auditResult.getData().passed()) {
                throw new BizException(ErrorCode.SENSITIVE_PROMPT, auditResult.getData().reason());
            }
        } catch (BizException e) { throw e; }
        catch (Exception e) {
            log.warn("Prompt audit failed (degraded): {}", e.getMessage());
        }

        // 3. 配额检查（通过 Feign）—— 审核通过后才扣减
        try {
            var quotaResult = userFeignClient.consumeQuota(userId, Map.of(
                    "type", request.getEngineType(),
                    "amount", request.getCount() != null ? request.getCount() : 1
            ));
            if (quotaResult != null && quotaResult.getData() != null && !quotaResult.getData()) {
                throw new BizException(ErrorCode.QUOTA_EXCEEDED);
            }
        } catch (BizException e) { throw e; }
        catch (Exception e) {
            log.warn("Quota check failed (degraded): {}", e.getMessage());
        }

        // 3.5 RAG 知识库增强（审核后，只增强已过审 prompt；检索失败降级不影响任务）
        String effectivePrompt = request.getPrompt();
        List<RagReference> ragReferences = List.of();
        if (request.getDatasetId() != null && !request.getDatasetId().isBlank()) {
            try {
                RagSearchRequest searchRequest = new RagSearchRequest();
                searchRequest.setDatasetId(request.getDatasetId());
                searchRequest.setQuery(request.getPrompt());
                searchRequest.setTopK(request.getRagTopK() != null ? request.getRagTopK() : 3);
                RagSearchResult searchResult = ragService.search(userId, tenantId, searchRequest);
                ragReferences = searchResult.getChunks();
                effectivePrompt = request.getPrompt() + ragService.buildReferencePrompt(ragReferences);
            } catch (Exception e) {
                log.warn("RAG prompt enhancement degraded: {}", e.getMessage());
            }
        }

        // 4. 构建任务
        AgentTask task = new AgentTask();
        task.setUserId(userId);
        task.setTenantId(tenantId);
        task.setWorkflowType(request.getWorkflowType() != null ? request.getWorkflowType() : AgentTask.WF_SINGLE);
        task.setStatus(AgentTask.STATUS_VALIDATING);
        task.setRetryCount(0);
        task.setPriority(0);

        // 5. 构建请求参数
        Map<String, Object> params = buildParams(request, intent, effectivePrompt, ragReferences);
        task.setRequestParams(JsonUtils.toJson(params));
        agentTaskMapper.insert(task);

        // 6. 构建工作流
        workflowEngine.buildWorkflow(task, params);

        // 7. 更新状态为 PENDING
        task.setStatus(AgentTask.STATUS_PENDING);
        agentTaskMapper.updateById(task);

        // 8. 事务提交后再分发第一步：防止事务回滚后 MQ 消息成为孤儿（步骤已建但任务/步骤不可见）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workflowEngine.advanceStep(task.getId());
            }
        });

        log.info("Task submitted: id={}, workflowType={}, engineType={}", task.getId(), task.getWorkflowType(), request.getEngineType());
        return task;
    }

    // ==================== 查询任务 ====================

    public AgentTask getTask(String taskId) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    /** 校验任务归属后返回（防 IDOR 越权：他人任务 → NO_PERMISSION） */
    public AgentTask getTask(String taskId, String userId) {
        AgentTask task = getTask(taskId);
        if (!task.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return task;
    }

    /** 校验任务归属后返回详情（防 IDOR 越权） */
    public Map<String, Object> getTaskDetail(String taskId, String userId) {
        getTask(taskId, userId);
        return getTaskDetail(taskId);
    }

    public List<WorkflowStep> getTaskSteps(String taskId) {
        return workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, taskId)
                        .orderByAsc(WorkflowStep::getStepOrder)
        );
    }

    public Map<String, Object> getTaskDetail(String taskId) {
        AgentTask task = getTask(taskId);
        List<WorkflowStep> steps = getTaskSteps(taskId);
        Map<String, Object> detail = new HashMap<>();
        detail.put("task", task);
        detail.put("steps", steps);
        return detail;
    }

    public PageResult<AgentTask> listTasks(String userId, int page, int size, String status) {
        Page<AgentTask> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<AgentTask> wrapper = new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(AgentTask::getStatus, status);
        }
        wrapper.orderByDesc(AgentTask::getCreatedAt);

        Page<AgentTask> result = agentTaskMapper.selectPage(mpPage, wrapper);
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    // ==================== 取消任务 ====================

    @Transactional
    public void deleteTask(String taskId, String userId) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!task.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 删除关联的步骤
        workflowStepMapper.delete(new LambdaQueryWrapper<WorkflowStep>()
                .eq(WorkflowStep::getTaskId, taskId));
        // 删除任务
        agentTaskMapper.deleteById(taskId);
        log.info("Task deleted: id={}", taskId);
    }

    public void cancelTask(String taskId, String userId) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        if (!task.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 只能取消未完成的任务
        if (AgentTask.STATUS_DONE.equals(task.getStatus())
                || AgentTask.STATUS_FAILED.equals(task.getStatus())
                || AgentTask.STATUS_CANCELLED.equals(task.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务已完成或已取消");
        }

        task.setStatus(AgentTask.STATUS_CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        agentTaskMapper.updateById(task);

        // 将 PENDING 步骤标记为 SKIPPED
        List<WorkflowStep> pendingSteps = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, taskId)
                        .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PENDING)
        );
        for (WorkflowStep step : pendingSteps) {
            step.setStatus(WorkflowStep.STATUS_SKIPPED);
            workflowStepMapper.updateById(step);
        }

        log.info("Task cancelled: id={}", taskId);
    }

    // ==================== 更新任务状态 ====================

    public void updateTaskStatus(String taskId, String status, String result) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task == null) return;
        task.setStatus(status);
        if (result != null) {
            task.setResult(result);
        }
        if (AgentTask.STATUS_DONE.equals(status) || AgentTask.STATUS_FAILED.equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        agentTaskMapper.updateById(task);
    }

    // ========== 辅助方法 ==========

    private Map<String, Object> buildParams(TaskSubmitRequest request, Map<String, String> intent,
                                            String effectivePrompt, List<RagReference> ragReferences) {
        Map<String, Object> params = new HashMap<>();
        params.put("prompt", effectivePrompt);
        if (!ragReferences.isEmpty()) {
            params.put("originalPrompt", request.getPrompt());
            params.put("ragReferences", ragReferences);
        }
        params.put("engineType", request.getEngineType());
        params.put("workflowType", request.getWorkflowType());
        params.put("modelProvider", request.getModelProvider());
        params.put("count", request.getCount());
        params.put("size", request.getSize() != null ? request.getSize() : "1024x1024");
        params.put("style", intent.getOrDefault("style", "default"));
        params.put("format", request.getFormat() != null ? request.getFormat() : "png");
        params.put("quality", request.getQuality() != null ? request.getQuality() : "standard");
        params.put("negativePrompt", request.getNegativePrompt());
        params.put("batchCount", request.getBatchCount());
        params.put("duration", request.getDuration());
        params.put("fps", request.getFps());
        params.put("resolution", request.getResolution());
        params.put("pipelineSteps", request.getPipelineSteps());
        return params;
    }
}
