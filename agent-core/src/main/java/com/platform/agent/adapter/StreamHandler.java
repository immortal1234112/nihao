package com.platform.agent.adapter;

/**
 * 流式对话回调。适配器在收到增量文本时回调，调用方负责转发（SSE 推送）与累积。
 */
public interface StreamHandler {

    /** 正文增量 */
    void onDelta(String delta);

    /**
     * 深度思考增量（推理过程）。
     * <p>
     * 预留缝：本轮不实现。将来在 {@code AbstractOpenAiCompatAdapter.extractDelta}
     * 里读取 {@code choices[0].delta.reasoning_content}（DeepSeek 系）或 Claude 的
     * thinking 块后转调此处，前端复用同一套 phase 事件通道加一个可折叠面板即可。
     */
    default void onReasoning(String reasoning) {
    }

    /**
     * 调用方是否已取消（客户端断开 / 超时）。
     * 适配器应在每个增量处检查，尽快停止读取下游响应。
     */
    default boolean isCancelled() {
        return false;
    }
}
