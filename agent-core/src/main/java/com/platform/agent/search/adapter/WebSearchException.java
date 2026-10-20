package com.platform.agent.search.adapter;

/**
 * 联网搜索异常：上游调用失败（网络、鉴权、限流、服务端错误）。
 * <p>
 * 编排层（WebSearchService）统一 catch 它做降级——联网搜索是「增强」不是「阻塞」，
 * 失败必须让对话照常进行，与 RAG 检索、记忆注入的降级范式一致。
 */
public class WebSearchException extends RuntimeException {

    /** 上游 HTTP 状态码；网络异常等无状态码的场景为 0 */
    private final int statusCode;

    /** 上游错误码（如 invalid_request_error）；无则为 null */
    private final String code;

    public WebSearchException(String message, int statusCode, String code) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.code = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getCode() {
        return code;
    }

    /**
     * 是否值得重试。
     * <p>
     * 上游文档明确：408 / 429 / 5xx 等待后重试；其余 4xx（400 参数错、401 Key 错、
     * 402 欠费、403 策略拒绝、404 抓不到正文）改了请求才有意义，无脑重试只是浪费配额。
     */
    public boolean retryable() {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }
}
