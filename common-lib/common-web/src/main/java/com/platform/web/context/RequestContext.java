package com.platform.web.context;

/**
 * 请求上下文 —— 持有当前请求的 requestId。
 * 基于 ThreadLocal，请求结束后由拦截器清理。
 */
public final class RequestContext {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId) {
        CONTEXT.set(requestId);
    }

    public static String get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
