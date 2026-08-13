package com.platform.web.config;

import com.platform.web.interceptor.InternalAuthInterceptor;
import com.platform.web.interceptor.RequestIdInterceptor;
import com.platform.web.interceptor.TenantContextInterceptor;
import com.platform.web.interceptor.UserContextInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 —— 注册上下文拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private InternalAuthInterceptor internalAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestIdInterceptor())
                .addPathPatterns("/api/**")
                .order(0);

        registry.addInterceptor(new TenantContextInterceptor())
                .addPathPatterns("/api/**")
                .order(1);

        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/**")
                .order(2);

        // 内部接口鉴权（X-Internal-Token）：只拦服务间 Feign 直连的内部端点
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/api/v1/internal/**")
                .order(3);
    }
}
