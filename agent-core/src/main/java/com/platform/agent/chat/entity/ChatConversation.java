package com.platform.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 对话会话实体。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("title")
    private String title;

    @TableField("provider")
    private String provider;

    @TableField("dataset_id")
    private String datasetId;

    /**
     * 绑定的记忆空间（{@link MemorySpace}）。
     * <p>
     * 创建对话时确定，<b>创建后不可变更</b>——中途换空间会导致上下文撕裂（前半段用工作记忆、
     * 后半段用个人记忆），要换空间就新建对话。存量会话为 NULL，运行时按默认空间解析。
     */
    @TableField("space_id")
    private String spaceId;

    @TableField("system_prompt")
    private String systemPrompt;

    @TableField("message_count")
    private Integer messageCount;
}
