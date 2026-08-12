package com.platform.security.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    // 必须通过环境变量 JWT_SECRET 显式配置(≥32 字符),JwtUtils 构造时校验,弱密钥拒绝启动
    private String secret = "";
    private long accessTokenTtl = 7200;
    private long refreshTokenTtl = 604800;
}
