package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

    /** 步骤失败最大重试次数（任务级 retry_count 计数） */
    private static final int MAX_RETRIES = 3;

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
                        // 步骤参数 = 主请求参数（prompt/size/format 等）+ 步骤 params 覆盖（扁平化）。
                        // 引擎消费端按扁平顶层 key 解析（如 params.get("prompt")），
                        // 直接存 stepDef 会导致 prompt/modelProvider 全为 null → 上游 API 422。
                        Map<String, Object> stepParams = new HashMap<>(params);
                        Object sp = stepDef.get("params");
                        if (sp instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> stepParams.put((String) k, v));
                        }
                        stepParams.remove("pipelineSteps");
                        WorkflowStep step = createStep(task.getId(), i, type, JsonUtils.toJson(stepParams));
                        steps.add(step);
                    }
                }
                // 无 AUDIT 步骤：全库无 REVIEWING 产生路径、无 UI 消费，创建后任务必卡 PROCESSING
            }

            case AgentTask.WF_BATCH -> {
                // N 个并行生成步骤（无 POST_PROCESS/AUDIT，同 SINGLE 原因）
                Object batchCountObj = params.get("batchCount");
                int batchCount = batchCountObj instanceof Number n ? n.intValue() : 2;
                for (int i = 0; i < batchCount; i++) {
                    WorkflowStep step = createStep(
                            task.getId(), i,
                            "IMAGE".equals(engineType) ? WorkflowStep.TYPE_IMAGE : WorkflowStep.TYPE_VIDEO,
                            JsonUtils.toJson(params)
                    );
                    steps.add(step);
                }
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
            // BATCH: 全部步骤均为生成步骤，一次性推进所有 PENDING（CAS 保证不重复分发）
            for (WorkflowStep step : pendingSteps) {
                dispatchStep(step);
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
        // CAS：仅当步骤仍为 PENDING 才置 PROCESSING，防止并发回执触发的重复分发（重复扣费）
        boolean dispatched = workflowStepMapper.update(null, new LambdaUpdateWrapper<WorkflowStep>()
                .eq(WorkflowStep::getId, step.getId())
                .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PENDING)
                .set(WorkflowStep::getStatus, WorkflowStep.STATUS_PROCESSING)
                .set(WorkflowStep::getStartedAt, LocalDateTime.now())) > 0;
        if (!dispatched) {
            log.warn("Step dispatch raced, skip: taskId={}, step={}", step.getTaskId(), step.getStepOrder());
            return;
        }

        // RAG_RETRIEVE 在本服务同步执行（本服务已标记 PROCESSING，见上方 CAS 更新）
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

        // 从任务补齐身份信息：引擎侧结果落库需要 userId/tenantId（结果文件归属），workflowType 供引擎侧幂等
        AgentTask task = agentTaskMapper.selectById(step.getTaskId());
        if (task == null) {
            log.error("Task not found for dispatch: taskId={}", step.getTaskId());
            return;
        }

        TaskMessage message = new TaskMessage(
                step.getTaskId(),
                task.getUserId(), task.getTenantId(), task.getWorkflowType(),
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
            step.setOutput(JsonUtils.toJson(Map.of("error", e.getMessage())));
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
     * 处理步骤失败：任务级重试计数（agent_task.retry_count，原子自增）≤ MAX_RETRIES 则重试，
     * 否则终失败（步骤 FAILED、其余 PENDING 步骤 SKIPPED、任务 FAILED）。
     * 计数不再写回 step.input —— 原实现 JSON 解析失败被空 catch 吞掉，计数丢失导致无限重试。
     */
    public void handleStepFailure(String taskId, int stepOrder, String errorMsg) {
        WorkflowStep step = findStep(taskId, stepOrder);
        if (step == null) return;

        // 原子自增，避免并发回执重复计数
        agentTaskMapper.update(null, new LambdaUpdateWrapper<AgentTask>()
                .eq(AgentTask::getId, taskId)
                .setSql("retry_count = IFNULL(retry_count, 0) + 1"));
        AgentTask task = agentTaskMapper.selectById(taskId);
        int attempt = task != null && task.getRetryCount() != null ? task.getRetryCount() : 1;

        if (attempt <= MAX_RETRIES) {
            // 重试：步骤回到 PENDING，重新分发
            step.setStatus(WorkflowStep.STATUS_PENDING);
            workflowStepMapper.updateById(step);
            advanceStep(taskId);
            log.info("Step retry: taskId={}, step={}, attempt={}", taskId, stepOrder, attempt);
        } else {
            // 终失败：流水线闭环，不再停滞
            step.setStatus(WorkflowStep.STATUS_FAILED);
            step.setOutput(JsonUtils.toJson(Map.of("error", errorMsg)));
            step.setFinishedAt(LocalDateTime.now());
            workflowStepMapper.updateById(step);
            failTask(taskId, errorMsg);
            log.warn("Step failed after retries: taskId={}, step={}", taskId, stepOrder);
        }
    }

    /** 终失败流程：其余 PENDING 步骤置 SKIPPED，任务置 FAILED（已取消的任务不覆盖） */
    private void failTask(String taskId, String errorMsg) {
        workflowStepMapper.update(null, new LambdaUpdateWrapper<WorkflowStep>()
                .eq(WorkflowStep::getTaskId, taskId)
                .eq(WorkflowStep::getStatus, WorkflowStep.STATUS_PENDING)
                .set(WorkflowStep::getStatus, WorkflowStep.STATUS_SKIPPED));
        AgentTask task = agentTaskMapper.selectById(taskId);
        if (task != null && !AgentTask.STATUS_CANCELLED.equals(task.getStatus())) {
            task.setStatus(AgentTask.STATUS_FAILED);
            task.setErrorMsg(errorMsg);
            task.setCompletedAt(LocalDateTime.now());
            agentTaskMapper.updateById(task);
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
}
