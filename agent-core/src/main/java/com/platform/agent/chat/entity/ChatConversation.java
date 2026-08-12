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

    @TableField("system_prompt")
    private String systemPrompt;

    @TableField("message_count")
    private Integer messageCount;
}
