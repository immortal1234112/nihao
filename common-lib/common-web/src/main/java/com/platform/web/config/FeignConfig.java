package com.platform.web.config;

import com.platform.web.context.RequestContext;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 配置 —— 调用其他服务时自动透传请求头。
 */
@Configuration
public class FeignConfig {

    /** 服务间内部接口鉴权令牌，与各服务 internal.auth-token 一致（未配置则不附加） */
    @Value("${internal.auth-token:}")
    private String internalToken;

    @Bean
    public RequestInterceptor contextInterceptor() {
        return (RequestTemplate template) -> {
            // 附加服务间鉴权令牌（配合 InternalAuthInterceptor）。
            // 注意：feign 12.x 中 @FeignClient 的 path 属性被合并进 Target.url，
            // 拦截器 apply 阶段的 template.path() 只有方法级路径（如 /upload），
            // 无法用前缀判断是否内部调用 —— 本项目全部 Feign 客户端均为服务间内部调用，无条件附加。
            if (internalToken != null && !internalToken.isBlank()) {
                template.header("X-Internal-Token", internalToken);
            }

            String requestId = RequestContext.get();
            if (requestId != null) {
                template.header("X-Request-Id", requestId);
            }

            String tenantId = TenantContext.get();
            if (tenantId != null) {
                template.header("X-Tenant-Id", tenantId);
            }

            UserContext.UserInfo userInfo = UserContext.get();
            if (userInfo != null) {
                template.header("X-User-Id", userInfo.userId());
                template.header("X-Username", userInfo.username());
                template.header("X-User-Roles", String.join(",", userInfo.roles()));
            }
        };
    }
}
