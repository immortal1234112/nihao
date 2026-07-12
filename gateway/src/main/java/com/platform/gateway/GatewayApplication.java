package com.platform.gateway;

import com.platform.security.jwt.JwtProperties;
import com.platform.security.jwt.JwtUtils;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.platform.gateway")
@EnableDiscoveryClient
@Import({JwtUtils.class, JwtProperties.class})
public class GatewayApplication {

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}