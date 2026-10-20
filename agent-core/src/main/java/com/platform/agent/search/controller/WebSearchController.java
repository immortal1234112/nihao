package com.platform.agent.search.controller;

import com.platform.agent.rag.dto.RagReference;
import com.platform.agent.search.dto.WebFetchResult;
import com.platform.agent.search.dto.WebSearchRequest;
import com.platform.agent.search.service.WebSearchOutcome;
import com.platform.agent.search.service.WebSearchService;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索接口 — /api/v1/search
 * <p>
 * 除了给对话做增强，也允许单独调用（调试搜索质量、被工作流步骤复用）。
 * 注意：搜索按请求计费，这些接口不做额外缓存，调用方注意频率。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class WebSearchController {

    private final WebSearchService webSearchService;

    /**
     * 联网搜索能力是否可用（是否已配置 API Key）。
     * 前端据此决定「联网搜索」开关是否可点。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("available", webSearchService.isAvailable());
        return Result.ok(m);
    }

    /**
     * 执行网页搜索，返回引用列表（与对话里的 references 同结构）。
     */
    @PostMapping
    public Result<Map<String, Object>> search(@RequestBody WebSearchRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "query 不能为空");
        }
        if (!webSearchService.isAvailable()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "联网搜索未配置：请在模型配置里填写 deepseek-search 的 API Key");
        }
        long start = System.currentTimeMillis();
        WebSearchOutcome outcome = webSearchService.searchWithOutcome(
                request.getQuery(), request.getOriQuestion(), request.effectiveTopK());
        // 调试接口不做降级：失败就明确报错，否则排查时同样会误以为「只是没搜到」
        if (outcome.hasError()) {
            throw new BizException(ErrorCode.PARAM_INVALID, outcome.getError());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("query", request.getQuery());
        result.put("references", outcome.getReferences());
        result.put("count", outcome.getReferences().size());
        result.put("durationMs", System.currentTimeMillis() - start);
        return Result.ok(result);
    }

    /**
     * 抓取单个网页正文（Markdown）。
     * 上游 404/403 等失败会返回 null，这里明确报错而不是静默返回空正文。
     */
    @PostMapping("/fetch")
    public Result<WebFetchResult> fetch(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 不能为空");
        }
        WebFetchResult result = webSearchService.fetch(url);
        if (result == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "网页抓取失败：目标不可达、被站点策略拦截或没有可提取的正文");
        }
        return Result.ok(result);
    }
}
