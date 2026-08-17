package com.platform.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 任务实体 —— 记录每个生成任务的全生命周期。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_task")
public class AgentTask extends BaseEntity {

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("workflow_type")
    private String workflowType;

    @TableField("status")
    private String status;

    @TableField("request_params")
    private String requestParams;

    @TableField("result")
    private String result;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("priority")
    private Integer priority;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 所属项目（阶段 1 新增，可空兼容存量） */
    @TableField("project_id")
    private String projectId;

    /** 输入资产（图生图/图生视频的来源） */
    @TableField("source_asset_id")
    private String sourceAssetId;

    /** 批次号，批量任务同组 */
    @TableField("batch_group_id")
    private String batchGroupId;

    /** 估算成本，任务完成时写入 */
    @TableField("cost")
    private java.math.BigDecimal cost;

    // ========== 工作流类型常量 ==========
    public static final String WF_SINGLE = "SINGLE";
    public static final String WF_PIPELINE = "PIPELINE";
    public static final String WF_BATCH = "BATCH";

    // ========== 状态常量 ==========
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_REVIEWING = "REVIEWING";
    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_REJECT = "REJECT";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
