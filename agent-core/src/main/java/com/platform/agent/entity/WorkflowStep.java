package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作流步骤实体 —— 记录每个任务的每一步执行情况。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_workflow_step")
public class WorkflowStep extends BaseEntity {

    @TableField("task_id")
    private String taskId;

    @TableField("step_order")
    private Integer stepOrder;

    @TableField("step_type")
    private String stepType;

    @TableField("status")
    private String status;

    @TableField("input")
    private String input;

    @TableField("output")
    private String output;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    // ========== 步骤类型常量 ==========
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_AUDIT = "AUDIT";
    public static final String TYPE_POST_PROCESS = "POST_PROCESS";

    // ========== 状态常量 ==========
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}
