package com.platform.storage;
public record UploadResult(String fileId, String objectKey, String url, Long fileSize) {}
