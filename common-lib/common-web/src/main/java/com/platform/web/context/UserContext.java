package com.platform.web.context;

import java.util.Collections;
import java.util.List;

/**
 * 用户上下文 —— 持有当前请求的用户信息。
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    private UserContext() {
    }

    public record UserInfo(String userId, String username, List<String> roles) {
    }

    public static void set(UserInfo userInfo) {
        CONTEXT.set(userInfo);
    }

    public static UserInfo get() {
        return CONTEXT.get();
    }

    public static String getUserId() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.userId() : null;
    }

    public static String getUsername() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.username() : null;
    }

    public static List<String> getRoles() {
        UserInfo info = CONTEXT.get();
        return info != null ? info.roles() : Collections.emptyList();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
