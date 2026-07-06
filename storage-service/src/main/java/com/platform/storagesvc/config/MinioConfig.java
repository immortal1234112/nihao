package com.platform.storagesvc.config;

import com.platform.storage.StorageAdapter;
import com.platform.storagesvc.adapter.MinioStorageAdapter;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置 —— 创建 MinioClient 和 StorageAdapter Bean。
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.bucket:agent-platform}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public StorageAdapter storageAdapter(MinioClient minioClient) {
        return new MinioStorageAdapter(minioClient, bucket);
    }
}
