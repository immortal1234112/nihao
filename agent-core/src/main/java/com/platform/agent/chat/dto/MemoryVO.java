package com.platform.agent.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆视图对象。
 */
@Data
public class MemoryVO {

    private String id;
    private String content;
    private String memoryType;
    private String category;
    private String sourceConversationId;
    private LocalDateTime createdAt;
}
