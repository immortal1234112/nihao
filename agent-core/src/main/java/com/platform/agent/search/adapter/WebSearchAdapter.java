package com.platform.agent.search.adapter;

import com.platform.agent.search.dto.WebFetchResult;
import com.platform.agent.search.dto.WebSearchItem;
import com.platform.agent.search.dto.WebSearchRequest;

import java.util.List;

/**
 * 联网搜索适配器：把「搜索网页」与「抓取网页正文」抽象成两种能力。
 * <p>
 * 目前只有 DeepSeek 官方 Search/Fetch 一种实现。之所以抽接口而不是直接写死，
 * 是因为上一版自建 SearXNG 的教训：绑定一个具体后端后，换供应商要动调用方。
 * 新增供应商只需实现本接口并注册为 Spring Bean，编排层（WebSearchService）不用改。
 */
public interface WebSearchAdapter {

    /** 供应商标识，需与 model_config.provider 对应（API Key 从这里取） */
    String provider();

    /**
     * 按 query 搜索网页。
     *
     * @return 结果列表；正常结束但无匹配时返回空列表（不是 null）
     * @throws WebSearchException 上游失败（已重试过仍失败）
     */
    List<WebSearchItem> search(WebSearchRequest request);

    /**
     * 抓取单个网页，返回 Markdown 正文。
     *
     * @throws WebSearchException 上游失败（已重试过仍失败）
     */
    WebFetchResult fetch(String url);

    /**
     * 是否已配置可用凭据。
     * 未配置时编排层直接跳过联网搜索，而不是每次请求都等一次鉴权失败。
     */
    boolean healthCheck();
}
