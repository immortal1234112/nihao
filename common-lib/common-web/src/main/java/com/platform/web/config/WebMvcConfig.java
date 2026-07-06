package com.platform.web.config;

import com.platform.web.interceptor.RequestIdInterceptor;
import com.platform.web.interceptor.TenantContextInterceptor;
import com.platform.web.interceptor.UserContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 —— 注册上下文拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

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
    }
}
