package com.platform.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 用户长期记忆实体。
 * embedding 为 JSON 数组，实体侧用 String 承载（与 rag_chunk.embedding 同风格），检索在应用层做余弦。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("user_memory")
public class UserMemory extends BaseEntity {

    public static final String TYPE_FACT = "FACT";
    public static final String TYPE_PREFERENCE = "PREFERENCE";

    @TableField("user_id")
    private String userId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("content")
    private String content;

    @TableField("memory_type")
    private String memoryType;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    @TableField("embedding")
    private String embeddingJson;

    @TableField("token_count")
    private Integer tokenCount;
}
