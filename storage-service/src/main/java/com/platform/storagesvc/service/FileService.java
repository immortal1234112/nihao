package com.platform.storagesvc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.core.base.PageResult;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.storage.StorageAdapter;
import com.platform.storage.UploadResult;
import com.platform.storagesvc.entity.StorageFile;
import com.platform.storagesvc.mapper.StorageFileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件管理服务 —— 协调 MinIO 适配器与数据库元数据。
 */
@Slf4j
@Service
public class FileService {

    private final StorageAdapter storageAdapter;
    private final StorageFileMapper storageFileMapper;

    public FileService(StorageAdapter storageAdapter, StorageFileMapper storageFileMapper) {
        this.storageAdapter = storageAdapter;
        this.storageFileMapper = storageFileMapper;
    }

    // ==================== 上传 ====================

    /**
     * 服务端上传 —— 接收 MultipartFile，存储到 MinIO 并记录元数据。
     * taskId 可选：Engine 生成结果上传时带入，Agent-Core 归档靠它按任务反查文件。
     */
    @Transactional
    public StorageFile upload(String userId, String tenantId, MultipartFile file, String storageClass, String taskId) {
        try {
            String objectKey = buildObjectKey(userId, file.getOriginalFilename());
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            byte[] bytes = file.getBytes();

            UploadResult result = storageAdapter.upload(
                    new java.io.ByteArrayInputStream(bytes),
                    objectKey,
                    contentType,
                    null
            );

            StorageFile sf = new StorageFile();
            sf.setUserId(userId);
            sf.setTenantId(tenantId);
            sf.setOriginalName(file.getOriginalFilename());
            sf.setObjectKey(objectKey);
            sf.setContentType(contentType);
            sf.setFileSize((long) bytes.length);
            sf.setFileMd5(md5(bytes));
            sf.setProvider(StorageFile.PROVIDER_MINIO);
            sf.setStorageClass(storageClass != null ? storageClass : StorageFile.CLASS_ASSET);
            sf.setVisibility(StorageFile.VISIBILITY_PRIVATE);
            sf.setSourceType(StorageFile.SOURCE_UPLOAD);
            // Engine 生成结果与任务关联（Agent-Core 归档按任务反查文件用）
            if (taskId != null && !taskId.isBlank()) {
                sf.setSourceTaskId(taskId);
            }

            if (StorageFile.CLASS_TEMP.equals(storageClass)) {
                sf.setExpireAt(LocalDateTime.now().plusHours(24));
            }

            storageFileMapper.insert(sf);
            log.info("File uploaded: id={}, objectKey={}, size={}", sf.getId(), objectKey, bytes.length);
            return sf;

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            throw new BizException(ErrorCode.STORAGE_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    // ==================== 预签名上传 ====================

    /**
     * 获取预签名上传 URL（客户端直传 MinIO）。
     */
    public Map<String, String> getPresignedUploadUrl(String userId, String tenantId, String filename, String contentType) {
        try {
            String objectKey = buildObjectKey(userId, filename);

            // 创建待确认记录
            StorageFile sf = new StorageFile();
            sf.setUserId(userId);
            sf.setTenantId(tenantId);
            sf.setOriginalName(filename);
            sf.setObjectKey(objectKey);
            sf.setContentType(contentType);
            sf.setProvider(StorageFile.PROVIDER_MINIO);
            sf.setStorageClass(StorageFile.CLASS_TEMP);
            sf.setVisibility(StorageFile.VISIBILITY_PRIVATE);
            sf.setSourceType(StorageFile.SOURCE_UPLOAD);
            // 预签名 TEMP 记录必须设过期时间，否则 cleanupExpiredTempFiles 永不清理（与 upload() 一致）
            sf.setExpireAt(LocalDateTime.now().plusHours(24));
            storageFileMapper.insert(sf);

            // 生成预签名 PUT URL（上传用 PUT 签名；GET 签名仅用于下载）
            String presignedUrl = storageAdapter.presignedUploadUrl(objectKey, Duration.ofMinutes(15));

            return Map.of(
                    "fileId", sf.getId(),
                    "uploadUrl", presignedUrl,
                    "objectKey", objectKey,
                    "expiresIn", "900"
            );
        } catch (Exception e) {
            log.error("Presigned URL generation failed: {}", e.getMessage(), e);
            throw new BizException(ErrorCode.STORAGE_ERROR, "生成上传链接失败: " + e.getMessage());
        }
    }

    /**
     * 确认预签名上传完成（带归属校验，防 IDOR：只能确认自己的文件）。
     */
    @Transactional
    public void confirmUpload(String fileId, String userId, String tenantId) {
        StorageFile sf = getOwned(fileId, userId, tenantId);
        try {
            // 获取实际文件信息
            com.platform.storage.FileMetadata meta = storageAdapter.metadata(sf.getObjectKey());
            sf.setFileSize(meta.fileSize());
            sf.setFileMd5(meta.md5());
            sf.setContentType(meta.contentType());
            sf.setStorageClass(StorageFile.CLASS_ASSET);
            sf.setExpireAt(null);
            storageFileMapper.updateById(sf);
            log.info("Upload confirmed: fileId={}, size={}", fileId, meta.fileSize());
        } catch (Exception e) {
            log.error("Confirm upload failed for {}: {}", fileId, e.getMessage());
            throw new BizException(ErrorCode.STORAGE_ERROR, "确认上传失败");
        }
    }

    // ==================== 下载 ====================

    /**
     * 下载文件。
     */
    public Resource download(String fileId) {
        StorageFile sf = storageFileMapper.selectById(fileId);
        if (sf == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        try {
            InputStream stream = storageAdapter.download(sf.getObjectKey());
            return new InputStreamResource(stream);
        } catch (Exception e) {
            log.error("Download failed for {}: {}", fileId, e.getMessage());
            throw new BizException(ErrorCode.STORAGE_ERROR, "文件下载失败");
        }
    }

    // ==================== 查询 ====================

    /**
     * 分页查询文件列表。
     */
    public PageResult<StorageFile> list(String userId, String tenantId, int page, int size) {
        Page<StorageFile> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<StorageFile> wrapper = new LambdaQueryWrapper<StorageFile>()
                .eq(StorageFile::getUserId, userId)
                .eq(StorageFile::getTenantId, tenantId)
                .orderByDesc(StorageFile::getCreatedAt);

        Page<StorageFile> result = storageFileMapper.selectPage(mpPage, wrapper);

        return PageResult.of(
                result.getRecords(),
                page, size, result.getTotal()
        );
    }

    /**
     * 查询单个文件。
     */
    /** 按任务 ID 查询产出文件（资产归档用）。 */
    public List<StorageFile> listByTask(String taskId) {
        return storageFileMapper.selectList(new LambdaQueryWrapper<StorageFile>()
                .eq(StorageFile::getSourceTaskId, taskId)
                .orderByAsc(StorageFile::getCreatedAt));
    }

    /** 批量查询文件（资产列表预览用）。 */
    public List<StorageFile> listByIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return storageFileMapper.selectList(new LambdaQueryWrapper<StorageFile>()
                .in(StorageFile::getId, fileIds));
    }

    public StorageFile getById(String fileId) {
        StorageFile sf = storageFileMapper.selectById(fileId);
        if (sf == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return sf;
    }

    /**
     * 归属校验后返回文件（防 IDOR：只能操作自己的文件）。
     * userId 缺失/不匹配 → NO_PERMISSION（网关恒注入 X-User-Id，缺失只可能来自绕过网关的直连，失败关闭）；
     * tenantId 仅在双方都非空时比对（兼容存量 tenantId 为 null 的旧数据）。
     */
    public StorageFile getOwned(String fileId, String userId, String tenantId) {
        StorageFile sf = getById(fileId);
        if (userId == null || userId.isBlank() || !userId.equals(sf.getUserId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (tenantId != null && !tenantId.isBlank()
                && sf.getTenantId() != null && !sf.getTenantId().isBlank()
                && !tenantId.equals(sf.getTenantId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return sf;
    }

    // ==================== 删除 ====================

    /**
     * 删除文件（MinIO + DB）。
     */
    @Transactional
    public void delete(String fileId) {
        StorageFile sf = storageFileMapper.selectById(fileId);
        if (sf == null) {
            return;
        }
        try {
            storageAdapter.delete(sf.getObjectKey());
            if (sf.getThumbnailKey() != null) {
                storageAdapter.delete(sf.getThumbnailKey());
            }
        } catch (Exception e) {
            log.warn("Failed to delete from storage: {}", e.getMessage());
        }
        storageFileMapper.deleteById(fileId);
        log.info("File deleted: id={}, objectKey={}", fileId, sf.getObjectKey());
    }

    // ==================== 定时清理 ====================

    /**
     * 每天凌晨 2 点清理过期的 TEMP 文件。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredTempFiles() {
        log.info("Starting cleanup of expired TEMP files...");
        List<StorageFile> expired = storageFileMapper.selectList(
                new LambdaQueryWrapper<StorageFile>()
                        .eq(StorageFile::getStorageClass, StorageFile.CLASS_TEMP)
                        .lt(StorageFile::getExpireAt, LocalDateTime.now())
        );

        int deleted = 0;
        for (StorageFile sf : expired) {
            try {
                storageAdapter.delete(sf.getObjectKey());
                storageFileMapper.deleteById(sf.getId());
                deleted++;
            } catch (Exception e) {
                log.warn("Failed to cleanup file {}: {}", sf.getId(), e.getMessage());
            }
        }
        log.info("Cleanup completed: {} expired TEMP files deleted", deleted);
    }

    // ========== 辅助方法 ==========

    private String buildObjectKey(String userId, String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return String.format("files/%s/%s/%s%s",
                userId,
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")),
                UUID.randomUUID().toString().replace("-", ""),
                ext);
    }

    private String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
