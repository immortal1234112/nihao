package com.platform.feign.dto;

public record QuotaDTO(String quotaType, long limit, long used) {}
