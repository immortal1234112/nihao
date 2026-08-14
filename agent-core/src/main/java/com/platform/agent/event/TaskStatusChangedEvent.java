package com.platform.agent.event;

/**
 * 任务状态变更事件 —— 发布点均为「写库成功之后」的汇聚点（submitTask 提交后 /
 * dispatchStep CAS 成功后 / 消费回执写库后 / cancelTask 置取消后）。
 * 前端订阅 WebSocket 后以「快照+增量」消费：收到事件 → 拉一次全量详情。
 */
public record TaskStatusChangedEvent(String taskId, String status, Integer stepOrder, long timestamp) {

    public TaskStatusChangedEvent(String taskId, String status) {
        this(taskId, status, null, System.currentTimeMillis());
    }

    public TaskStatusChangedEvent(String taskId, String status, Integer stepOrder) {
        this(taskId, status, stepOrder, System.currentTimeMillis());
    }
}
