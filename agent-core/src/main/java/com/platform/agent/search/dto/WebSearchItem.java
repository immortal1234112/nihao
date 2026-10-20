package com.platform.agent.search.dto;

import lombok.Data;

/**
 * 单条网页搜索结果。
 * <p>
 * 字段与上游 Search 接口一一对应，不做改名——上游新增字段时本地不用跟着动。
 * {@code summary}（600~800 字符）适合喂给模型，{@code snippet}（100~200 字符）
 * 适合前端展示，两者用途不同，不要混用。
 */
@Data
public class WebSearchItem {

    /** 排序分数 0~3，>=1.5 为优质结果 */
    private double rerankScore;

    /** 标题 */
    private String title;

    /** 结果链接 */
    private String url;

    /** 短摘要，适合前端展现 */
    private String snippet;

    /** 长摘要，适合给模型阅读 */
    private String summary;

    /** 网页正文（need_content=false 时为空串） */
    private String content;

    /** 站点名称，缺失为 null */
    private String siteName;

    /** 站点图标 URL，缺失为 null */
    private String siteIcon;

    /** 发布时间（上游为 UTC RFC3339），原样保留字符串，未知为 null */
    private String date;

    /** 权威性 0~4，-1 表示未知，>=3 为极权威 */
    private int authority;

    /**
     * 给模型阅读的最佳文本：优先正文，其次长摘要，最后短摘要。
     * 正文可能很长，调用方需自行截断。
     */
    public String bestText() {
        if (content != null && !content.isBlank()) return content;
        if (summary != null && !summary.isBlank()) return summary;
        return snippet == null ? "" : snippet;
    }
}
