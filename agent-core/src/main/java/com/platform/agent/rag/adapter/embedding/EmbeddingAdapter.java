package com.platform.agent.rag.adapter.embedding;

import java.util.List;

/**
 * Embedding 适配器接口。
 * 实现类自动被 Spring 收集（@Component），provider 与 model_config 表的 provider 对应。
 */
public interface EmbeddingAdapter {

    /** provider 名称，如 qwen-embedding / openai-embedding */
    String provider();

    /** 当前生效的模型名（配置覆盖后） */
    String modelName();

    /**
     * 批量向量化。
     * 调用方负责按 batchSize 切片；失败抛 RuntimeException，由处理器批次级重试。
     */
    List<List<Double>> embedBatch(List<String> texts);

    /** 向量维度（与数据集创建时锁定的 dimension 一致） */
    int dimension();

    boolean healthCheck();
}
