package com.platform.web.config;

import com.platform.web.context.RequestContext;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 配置 —— 调用其他服务时自动透传请求头。
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor contextInterceptor() {
        return (RequestTemplate template) -> {
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
