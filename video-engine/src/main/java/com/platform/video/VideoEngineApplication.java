package com.platform.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.platform")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.platform.feign")
public class VideoEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoEngineApplication.class, args);
    }
}
