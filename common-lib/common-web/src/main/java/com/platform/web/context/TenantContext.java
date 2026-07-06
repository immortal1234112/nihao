package com.platform.web.context;

/**
 * 租户上下文 —— 持有当前请求的 tenantId。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CONTEXT.set(tenantId);
    }

    public static String get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
