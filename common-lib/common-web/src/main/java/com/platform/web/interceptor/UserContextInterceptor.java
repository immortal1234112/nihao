package com.platform.web.interceptor;

import com.platform.web.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 用户上下文拦截器 —— 从请求头读取用户信息并注入 UserContext。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-Username");
        String rolesHeader = request.getHeader("X-User-Roles");

        if (userId != null && !userId.isBlank()) {
            List<String> roles = (rolesHeader != null && !rolesHeader.isBlank())
                    ? Arrays.asList(rolesHeader.split(","))
                    : Collections.emptyList();
            UserContext.set(new UserContext.UserInfo(userId, username, roles));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        UserContext.clear();
    }
}
