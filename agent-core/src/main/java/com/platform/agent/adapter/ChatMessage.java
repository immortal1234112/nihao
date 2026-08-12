package com.platform.agent.adapter;

/**
 * 多轮对话历史消息载体（传给 LLMAdapter 的历史，不含本次 userMessage）。
 */
public record ChatMessage(String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
