package com.platform.image;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.platform")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.platform.feign")
@MapperScan("com.platform.core.config")
public class ImageEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImageEngineApplication.class, args);
    }
}
