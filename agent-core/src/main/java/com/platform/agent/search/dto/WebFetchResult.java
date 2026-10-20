package com.platform.agent.search.dto;

import lombok.Data;

/**
 * 网页抓取结果（Fetch 接口）。
 */
@Data
public class WebFetchResult {

    /** 页面标题，缺失时为空串 */
    private String title;

    /** 抓取后的 Markdown 正文 */
    private String content;

    /** 实际请求的 URL（便于调用方回填引用） */
    private String url;
}
