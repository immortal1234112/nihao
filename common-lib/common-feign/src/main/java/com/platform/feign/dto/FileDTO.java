package com.platform.feign.dto;

public record FileDTO(String id, String objectKey, String url, String contentType, Long fileSize,
                      Integer width, Integer height, Integer durationMs) {}
