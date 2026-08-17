package com.platform.storagesvc.controller;

import com.platform.core.base.PageResult;
import com.platform.core.base.Result;
import com.platform.storagesvc.entity.StorageFile;
import com.platform.storagesvc.service.FileService;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件控制器 — /api/v1/files
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @AuditLog(action = "UPLOAD_FILE")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestPart MultipartFile file,
            @RequestParam(defaultValue = "ASSET") String storageClass) {

        StorageFile sf = fileService.upload(
                UserContext.getUserId(),
                TenantContext.get(),
                file,
                storageClass,
                null
        );

        Map<String, Object> result = Map.of(
                "id", sf.getId(),
                "objectKey", sf.getObjectKey(),
                "originalName", sf.getOriginalName(),
                "contentType", sf.getContentType(),
                "fileSize", sf.getFileSize(),
                "storageClass", sf.getStorageClass()
        );
        return Result.ok(result);
    }

    @GetMapping("/presign")
    public Result<Map<String, String>> getPresignedUrl(
            @RequestParam String filename,
            @RequestParam(defaultValue = "application/octet-stream") String contentType) {

        Map<String, String> presignResult = fileService.getPresignedUploadUrl(
                UserContext.getUserId(),
                TenantContext.get(),
                filename,
                contentType
        );
        return Result.ok(presignResult);
    }

    @PostMapping("/presign/callback")
    public Result<Void> presignCallback(@RequestParam String fileId) {
        // 归属校验（防 IDOR）：只能确认自己的文件（原实现无任何归属校验）
        fileService.confirmUpload(fileId, UserContext.getUserId(), TenantContext.get());
        return Result.ok();
    }

    @GetMapping
    public Result<PageResult<StorageFile>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResult<StorageFile> result = fileService.list(
                UserContext.getUserId(),
                TenantContext.get(),
                page,
                size
        );
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<StorageFile> getById(@PathVariable String id) {
        return Result.ok(fileService.getOwned(id, UserContext.getUserId(), TenantContext.get()));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        StorageFile sf = fileService.getOwned(id, UserContext.getUserId(), TenantContext.get());
        Resource resource = fileService.download(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        sf.getContentType() != null ? sf.getContentType() : "application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sf.getOriginalName() + "\"")
                .body(resource);
    }

    @AuditLog(action = "DELETE_FILE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        fileService.getOwned(id, UserContext.getUserId(), TenantContext.get());
        fileService.delete(id);
        return Result.ok();
    }
}
