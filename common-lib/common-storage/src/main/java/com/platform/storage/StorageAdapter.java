package com.platform.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface StorageAdapter {
    String providerName();
    boolean isAvailable();
    UploadResult upload(InputStream data, String objectKey, String contentType,
                        Map<String, String> metadata) throws Exception;
    String initiateMultipart(String objectKey, String contentType) throws Exception;
    PartResult uploadPart(String uploadId, int partNumber, InputStream data) throws Exception;
    UploadResult completeMultipart(String uploadId, List<PartResult> parts) throws Exception;
    void abortMultipart(String uploadId) throws Exception;
    InputStream download(String objectKey) throws Exception;
    String presignedUrl(String objectKey, Duration ttl) throws Exception;
    boolean exists(String objectKey) throws Exception;
    FileMetadata metadata(String objectKey) throws Exception;
    void delete(String objectKey) throws Exception;
    void batchDelete(List<String> objectKeys) throws Exception;
    InputStream thumbnail(String objectKey, int width, int height) throws Exception;
}
