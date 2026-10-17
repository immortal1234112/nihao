package com.platform.agent.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象。
 */
@Data
public class ConversationVO {

    private String id;
    private String title;
    private String provider;
    private String datasetId;
    /** 绑定的记忆空间（创建后不可变更） */
    private String spaceId;
    private String systemPrompt;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
