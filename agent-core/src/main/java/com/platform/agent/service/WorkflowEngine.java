package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentTask;
import com.platform.agent.entity.WorkflowStep;
import com.platform.agent.mapper.AgentTaskMapper;
import com.platform.agent.mapper.WorkflowStepMapper;
import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.rag.dto.RagSearchRequest;
import com.platform.agent.rag.dto.RagSearchResult;
import com.platform.agent.rag.service.RagService;
import com.platform.core.util.JsonUtils;
import com.platform.mq.exchange.ExchangeConstants;
import com.platform.mq.message.TaskMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流引擎 —— 构建步骤、推进步骤、处理失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowStepMapper workflowStepMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RagService ragService;

    // ==================== 构建工作流 ====================

    /**
     * 根据请求构建工作流步骤列表。
     */
    public List<WorkflowStep> buildWorkflow(AgentTask task, Map<String, Object> params) {
        String workflowType = task.getWorkflowType();
        String engineType = task.getRequestParams() != null
                ? extractEngineType(task.getRequestParams())
                : "IMAGE";

        List<WorkflowStep> steps = new ArrayList<>();

        switch (workflowType) {
            case AgentTask.WF_SINGLE -> {
                // 单任务：只有 1 个生成步骤（无审核，提速）
                WorkflowStep genStep = createStep(
                        task.getId(), 0,
                        "IMAGE".equals(engineType) ? WorkflowStep.TYPE_IMAGE : WorkflowStep.TYPE_VIDEO,
                        JsonUtils.toJson(params)
                );
                steps.add(genStep);
            }

            case AgentTask.WF_PIPELINE -> {
                // N 个顺序步骤
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pipelineSteps = (List<Map<String, Object>>) params.get("pipelineSteps");
                if (pipelineSteps != null) {
                    for (int i = 0; i < pipelineSteps.size(); i++) {
                        Map<String, Object> stepDef = pipelineSteps.get(i);
                        // 前端提交字段为 stepType，兼容旧数据/API 的 type（默认为 IMAGE）
                        String type = (String) stepDef.getOrDefault("stepType", stepDef.getOrDefault("type", WorkflowStep.TYPE_IMAGE));
                        WorkflowStep step = createStep(task.getId(), i, type, JsonUtils.toJson(stepDef));
                        steps.add(step);
                    }
                }
                // 最后加审核步骤
                steps.add(createStep(task.getId(), steps.size(), WorkflowStep.TYPE_AUDIT, "{}"));
            }

            case AgentTask.WF_BATCH -> {
                // N 个并行生成步骤 + 1 个汇总步骤
                int batchCount = params.containsKey("batchCount")
                        ? ((Number) params.get("batchCount")).intValue()
                        : 2;
                for (int i = 0; i < batchCount; i++) {
                    WorkflowStep step = createStep(
                            task.getId(), i,
                            "IMAGE".equals(engineType) ? WorkflowStep.TYPE_IMAGE : WorkflowStep.TYPE_VIDEO,
                            JsonUtils.toJson(params)
                    );
                    steps.add(step);
                }
                steps.add(createStep(task.getId(), batchCount, WorkflowStep.TYPE_POST_PROCESS, "{}"));
                steps.add(createStep(task.getId(), batchCount + 1, WorkflowStep.TYPE_AUDIT, "{}"));
            }
        }

        // 批量插入
        for (WorkflowStep step : steps) {
            workflowStepMapper.insert(step);
        }

        log.info("Workflow built: taskId={}, type={}, steps={}", task.getId(), workflowType, steps.size());
        return steps;
    }

    // ==================== 推进步骤 ====================

    /**
     * 找到下一个 PENDING 步骤并将其标记为 PROCESSING，然后发送 MQ 消息。
     */
    public void advanceStep(String taskId) {
        List<WorkflowStep> pendingSteps = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, taskId)
                        .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PENDING)
                        .orderByAsc(WorkflowStep::getStepOrder)
        );

        if (pendingSteps.isEmpty()) {
            // 所有步骤完成
            log.info("All steps completed for task: {}", taskId);
            return;
        }

        // 对于 BATCH 模式，同时推进所有并行的 PENDING 生成步骤
        // 对于 SINGLE/PIPELINE，只推进第一个
        WorkflowStep firstPending = pendingSteps.get(0);

        if (AgentTask.WF_BATCH.equals(getTaskWorkflowType(taskId))) {
            // BATCH: 推进所有非 AUDIT/POST_PROCESS 的 PENDING 步骤
            for (WorkflowStep step : pendingSteps) {
                if (!WorkflowStep.TYPE_AUDIT.equals(step.getStepType())
                        && !WorkflowStep.TYPE_POST_PROCESS.equals(step.getStepType())) {
                    dispatchStep(step);
                }
            }
        } else {
            // SINGLE / PIPELINE: 只推进第一个
            dispatchStep(firstPending);
        }
    }

    /**
     * 分发单个步骤到 MQ。
     */
    private void dispatchStep(WorkflowStep step) {
        step.setStatus(WorkflowStep.STATUS_PROCESSING);
        step.setStartedAt(LocalDateTime.now());
        workflowStepMapper.updateById(step);

        // RAG_RETRIEVE 在本服务同步执行（本服务已标记 PROCESSING，见上方 updateById）
        if (WorkflowStep.TYPE_RAG_RETRIEVE.equals(step.getStepType())) {
            handleRagRetrieve(step);
            return;
        }

        // 确定引擎类型和路由键
        String engineType = step.getStepType();
        String routingKey;
        if (WorkflowStep.TYPE_IMAGE.equals(step.getStepType())) {
            routingKey = "task.image.single";
        } else if (WorkflowStep.TYPE_VIDEO.equals(step.getStepType())) {
            routingKey = "task.video.single";
        } else {
            // AUDIT / POST_PROCESS 在本服务处理，不发 MQ
            log.debug("Step type {} does not require MQ dispatch", step.getStepType());
            return;
        }

        TaskMessage message = new TaskMessage(
                step.getTaskId(),
                null, null, null, // userId/tenantId/workflowType 从 task 获取
                engineType,
                parseParams(step.getInput()),
                step.getStepOrder(),
                0
        );

        rabbitTemplate.convertAndSend(ExchangeConstants.TASK_EXCHANGE, routingKey, message);
        log.info("Step dispatched: taskId={}, step={}, engineType={}", step.getTaskId(), step.getStepOrder(), engineType);
    }

    // ==================== RAG 检索步骤 ====================

    /**
     * 同步执行 RAG_RETRIEVE 步骤：检索 → 步骤输出写 refs → 注入后续 PENDING 步骤的 ragContext → 推进。
     * 必须正确闭环（DONE + advanceStep），不可照抄 AUDIT/POST_PROCESS 分支。
     */
    private void handleRagRetrieve(WorkflowStep step) {
        String taskId = step.getTaskId();
        try {
            Map<String, Object> input = parseParams(step.getInput());
            String datasetId = (String) input.get("datasetId");
            String query = (String) input.get("query");
            Integer topK = input.get("topK") instanceof Number n ? n.intValue() : 3;
            if (datasetId == null || datasetId.isBlank()) {
                throw new RuntimeException("RAG_RETRIEVE 步骤缺少 datasetId");
            }

            AgentTask task = agentTaskMapper.selectById(taskId);
            if (task == null) {
                throw new RuntimeException("任务不存在: " + taskId);
            }
            if (query == null || query.isBlank()) {
                query = extractPrompt(task.getRequestParams());
            }
            if (query == null || query.isBlank()) {
                throw new RuntimeException("RAG_RETRIEVE 步骤缺少 query（且任务无 prompt 可作缺省）");
            }

            RagSearchRequest searchRequest = new RagSearchRequest();
            searchRequest.setDatasetId(datasetId);
            searchRequest.setQuery(query);
            searchRequest.setTopK(topK);
            RagSearchResult searchResult = ragService.search(task.getUserId(), task.getTenantId(), searchRequest);
            List<RagReference> refs = searchResult.getChunks();

            // 步骤输出 refs（JSON 序列化为 List<RagReference>）
            Map<String, Object> output = new HashMap<>();
            output.put("query", query);
            output.put("chunks", refs);
            step.setOutput(JsonUtils.toJson(output));
            step.setStatus(WorkflowStep.STATUS_DONE);
            step.setFinishedAt(LocalDateTime.now());
            workflowStepMapper.updateById(step);

            // 注入后续 PENDING 步骤的 ragContext（下游引擎分发时经 TaskMessage.params 自动携带）
            injectRagContext(taskId, refs);

            log.info("RAG_RETRIEVE step done: taskId={}, step={}, hits={}", taskId, step.getStepOrder(), refs.size());
        } catch (Exception e) {
            // RAG 是增强不是阻塞：标记 FAILED 但不阻止流水线继续
            log.error("RAG_RETRIEVE step failed: taskId={}, step={}", taskId, step.getStepOrder(), e);
            step.setStatus(WorkflowStep.STATUS_FAILED);
            step.setOutput("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            step.setFinishedAt(LocalDateTime.now());
            workflowStepMapper.updateById(step);
        }
        advanceStep(taskId);
    }

    /** 将检索结果写入同任务其余 PENDING 步骤的 input.ragContext */
    private void injectRagContext(String taskId, List<RagReference> refs) {
        if (refs == null || refs.isEmpty()) return;
        List<WorkflowStep> pending = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, taskId)
                        .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PENDING));
        for (WorkflowStep s : pending) {
            try {
                // parseParams 可能返回不可变 Map.of()，必须拷贝后修改
                Map<String, Object> inputMap = new HashMap<>(parseParams(s.getInput()));
                inputMap.put("ragContext", Map.of("chunks", refs));
                s.setInput(JsonUtils.toJson(inputMap));
                workflowStepMapper.updateById(s);
            } catch (Exception e) {
                log.warn("Inject ragContext failed: taskId={}, step={}", taskId, s.getStepOrder(), e);
            }
        }
    }

    private String extractPrompt(String requestParams) {
        if (requestParams == null || requestParams.isBlank()) return null;
        try {
            Map<String, Object> map = JsonUtils.fromJson(requestParams, Map.class);
            Object prompt = map.get("prompt");
            return prompt != null ? prompt.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 处理失败 ====================

    /**
     * 处理步骤失败：重试 ≤ 3 次，否则标记 FAILED。
     */
    public void handleStepFailure(String taskId, int stepOrder, String errorMsg) {
        WorkflowStep step = findStep(taskId, stepOrder);
        if (step == null) return;

        // 从 task 获取重试次数（通过 AgentTaskMapper）
        // 简化设计：在当前失败的步骤上递增重试
        int currentRetries = step.getInput() != null && step.getInput().contains("\"retryCount\"")
                ? extractRetryCount(step.getInput())
                : 0;

        if (currentRetries < 3) {
            // 重试
            step.setStatus(WorkflowStep.STATUS_PENDING);
            // 增加重试计数到 input
            try {
                Map<String, Object> inputMap = parseParams(step.getInput());
                inputMap.put("retryCount", currentRetries + 1);
                step.setInput(JsonUtils.toJson(inputMap));
            } catch (Exception ignored) {}
            workflowStepMapper.updateById(step);

            // 通过 advanceStep 重新分发
            advanceStep(taskId);
            log.info("Step retry: taskId={}, step={}, retry={}", taskId, stepOrder, currentRetries + 1);
        } else {
            step.setStatus(WorkflowStep.STATUS_FAILED);
            step.setOutput("{\"error\": \"" + escapeJson(errorMsg) + "\"}");
            step.setFinishedAt(LocalDateTime.now());
            workflowStepMapper.updateById(step);
            log.warn("Step failed after retries: taskId={}, step={}", taskId, stepOrder);
        }
    }

    // ========== 辅助方法 ==========

    private WorkflowStep createStep(String taskId, int order, String type, String input) {
        WorkflowStep step = new WorkflowStep();
        step.setTaskId(taskId);
        step.setStepOrder(order);
        step.setStepType(type);
        step.setStatus(WorkflowStep.STATUS_PENDING);
        step.setInput(input);
        return step;
    }

    private WorkflowStep findStep(String taskId, int stepOrder) {
        List<WorkflowStep> steps = workflowStepMapper.selectList(
                new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getTaskId, taskId)
                        .eq(WorkflowStep::getStepOrder, stepOrder)
        );
        return steps.isEmpty() ? null : steps.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return JsonUtils.fromJson(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractEngineType(String requestParams) {
        try {
            Map<String, Object> map = JsonUtils.fromJson(requestParams, Map.class);
            return (String) map.getOrDefault("engineType", "IMAGE");
        } catch (Exception e) {
            return "IMAGE";
        }
    }

    private String getTaskWorkflowType(String taskId) {
        AgentTask task = agentTaskMapper.selectById(taskId);
        return task != null ? task.getWorkflowType() : AgentTask.WF_SINGLE;
    }

    private int extractRetryCount(String input) {
        try {
            Map<String, Object> map = JsonUtils.fromJson(input, Map.class);
            Object count = map.get("retryCount");
            return count instanceof Number ? ((Number) count).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
