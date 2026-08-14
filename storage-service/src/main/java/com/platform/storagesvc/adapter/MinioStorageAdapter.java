package com.platform.storagesvc.adapter;

import com.platform.storage.FileMetadata;
import com.platform.storage.PartResult;
import com.platform.storage.StorageAdapter;
import com.platform.storage.UploadResult;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MinIO 存储适配器实现。
 */
@Slf4j
public class MinioStorageAdapter implements StorageAdapter {

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioStorageAdapter(MinioClient minioClient, String bucketName) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket {}: {}", bucketName, e.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "minio";
    }

    @Override
    public boolean isAvailable() {
        try {
            minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public UploadResult upload(InputStream data, String objectKey, String contentType,
                               Map<String, String> metadata) throws Exception {
        PutObjectArgs.Builder builder = PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .contentType(contentType);

        if (data instanceof ByteArrayInputStream bais) {
            builder.stream(bais, bais.available(), -1);
        } else {
            // 对于不确定大小的流，使用 -1（需 MinIO 服务端支持）
            byte[] bytes = data.readAllBytes();
            builder.stream(new ByteArrayInputStream(bytes), bytes.length, -1);
        }

        if (metadata != null) {
            builder.userMetadata(metadata);
        }

        minioClient.putObject(builder.build());

        String url = presignedUrl(objectKey, Duration.ofHours(1));
        return new UploadResult(UUID.randomUUID().toString(), objectKey, url, null);
    }

    @Override
    public String initiateMultipart(String objectKey, String contentType) throws Exception {
        // MinIO SDK 不直接暴露 createMultipartUpload，通过组合模式预留
        throw new UnsupportedOperationException("Multipart upload not yet implemented");
    }

    @Override
    public PartResult uploadPart(String uploadId, int partNumber, InputStream data) throws Exception {
        throw new UnsupportedOperationException("Multipart upload not yet implemented");
    }

    @Override
    public UploadResult completeMultipart(String uploadId, List<PartResult> parts) throws Exception {
        throw new UnsupportedOperationException("Multipart upload not yet implemented");
    }

    @Override
    public void abortMultipart(String uploadId) throws Exception {
        throw new UnsupportedOperationException("Multipart upload not yet implemented");
    }

    @Override
    public InputStream download(String objectKey) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build());
    }

    @Override
    public String presignedUrl(String objectKey, Duration ttl) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .method(Method.GET)
                .expiry((int) ttl.toSeconds())
                .build());
    }

    @Override
    public String presignedUploadUrl(String objectKey, Duration ttl) throws Exception {
        // 预签名上传必须签 PUT 方法（原实现误签 GET，客户端直传会被 MinIO 拒绝）
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .method(Method.PUT)
                .expiry((int) ttl.toSeconds())
                .build());
    }

    @Override
    public boolean exists(String objectKey) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public FileMetadata metadata(String objectKey) throws Exception {
        StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build());

        return new FileMetadata(
                objectKey,
                stat.contentType(),
                stat.size(),
                stat.etag(),
                null, null, null,
                LocalDateTime.now()
        );
    }

    @Override
    public void delete(String objectKey) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .build());
    }

    @Override
    public void batchDelete(List<String> objectKeys) throws Exception {
        for (String key : objectKeys) {
            delete(key);
        }
    }

    @Override
    public InputStream thumbnail(String objectKey, int width, int height) throws Exception {
        try (InputStream original = download(objectKey)) {
            BufferedImage image = ImageIO.read(original);
            if (image == null) {
                throw new IOException("Cannot read image: " + objectKey);
            }

            // 计算等比缩放
            double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
            int scaledWidth = (int) (image.getWidth() * scale);
            int scaledHeight = (int) (image.getHeight() * scale);

            BufferedImage thumbnail = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumbnail.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(image, 0, 0, scaledWidth, scaledHeight, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "png", baos);
            return new ByteArrayInputStream(baos.toByteArray());
        }
    }
}
