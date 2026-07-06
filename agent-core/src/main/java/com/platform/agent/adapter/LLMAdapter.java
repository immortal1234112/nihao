package com.platform.agent.adapter;

import java.util.Map;

/**
 * 大模型文本生成适配器接口。
 * 所有 LLM（DeepSeek、千问、GPT 等）实现此接口即可接入平台。
 */
public interface LLMAdapter {

    /** 模型名称，如 "deepseek-chat", "qwen-turbo" */
    String modelName();

    /** 模型提供商，如 "deepseek", "qwen", "openai" */
    String provider();

    /** 同步对话（非流式） */
    String chat(String systemPrompt, String userMessage, Map<String, Object> options);

    /** 健康检查 */
    boolean healthCheck();
}
