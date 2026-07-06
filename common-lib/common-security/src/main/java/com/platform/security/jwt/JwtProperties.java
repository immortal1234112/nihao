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
    private String secret = "change-me-in-production-min-32-chars";
    private long accessTokenTtl = 7200;
    private long refreshTokenTtl = 604800;
}
