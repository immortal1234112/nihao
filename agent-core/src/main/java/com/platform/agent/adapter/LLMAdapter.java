package com.platform.agent.adapter;

import java.util.List;
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

    /**
     * 同步对话（非流式）。
     *
     * @param history    已截断的历史消息（不含本次 userMessage，最后一条为 assistant），可为空
     * @param userMessage 本次用户消息
     */
    String chat(String systemPrompt, List<ChatMessage> history, String userMessage, Map<String, Object> options);

    /** 旧签名兼容：委托新签名（空历史 = 原单轮行为） */
    default String chat(String systemPrompt, String userMessage, Map<String, Object> options) {
        return chat(systemPrompt, List.of(), userMessage, options);
    }

    /** 健康检查 */
    boolean healthCheck();
}
