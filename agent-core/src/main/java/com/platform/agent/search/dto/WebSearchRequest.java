package com.platform.agent.search.dto;

import lombok.Data;

/**
 * 网页搜索请求。
 */
@Data
public class WebSearchRequest {

    /** 搜索词，1~128 个 Unicode 字符，不能全为空白 */
    private String query;

    /** 返回条数，1~50，缺省 10 */
    private Integer count;

    /**
     * 触发搜索的用户原始问题（1~128 字符）。
     * 传入有助于搜索服务理解意图——例如 query 是被模型改写过的关键词串，
     * 而原始问题保留了上下文。可为空。
     */
    private String oriQuestion;

    /** 是否返回网页正文（会显著增加响应体与延迟），默认 false */
    private Boolean needContent;

    /**
     * 本地截断条数（不传给上游）。
     * 计费按请求次数而非条数，所以 count 传大再本地裁剪没有成本优势，
     * 这里只用于「取 10 条、只用 3 条」减少下游拼装量。
     */
    private Integer topK;

    public int effectiveCount() {
        if (count == null) return 10;
        return Math.max(1, Math.min(50, count));
    }

    public boolean effectiveNeedContent() {
        return Boolean.TRUE.equals(needContent);
    }

    public int effectiveTopK() {
        if (topK == null || topK <= 0) return effectiveCount();
        return Math.min(topK, effectiveCount());
    }
}
