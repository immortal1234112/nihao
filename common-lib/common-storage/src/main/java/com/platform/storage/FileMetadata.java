package com.platform.storage;
import java.time.LocalDateTime;

public record FileMetadata(String objectKey, String contentType, Long fileSize, String md5,
                           Integer width, Integer height, Integer durationMs,
                           LocalDateTime lastModified) {}
