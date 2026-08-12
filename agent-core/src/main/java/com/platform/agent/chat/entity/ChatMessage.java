package com.platform.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 对话消息实体。
 * referencesJson 存 RAG 引用列表（RagReference[]），实体侧 String 承载（与 rag_chunk.embedding 同风格）。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @TableField("conversation_id")
    private String conversationId;

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("references_json")
    private String referencesJson;

    @TableField("token_count")
    private Integer tokenCount;
}
