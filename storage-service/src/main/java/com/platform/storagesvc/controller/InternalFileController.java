package com.platform.storagesvc.controller;

import com.platform.core.base.Result;
import com.platform.feign.dto.FileDTO;
import com.platform.storage.UploadResult;
import com.platform.storagesvc.entity.StorageFile;
import com.platform.storagesvc.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 内部 Feign 调用控制器 — /api/v1/internal/files
 * 供 Image-Engine / Video-Engine 等内部服务调用。
 */
@RestController
@RequestMapping("/api/v1/internal/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileService fileService;

    /**
     * 获取文件信息。
     */
    @GetMapping("/{fileId}")
    public Result<FileDTO> getFile(@PathVariable String fileId) {
        StorageFile sf = fileService.getById(fileId);
        FileDTO dto = new FileDTO(
                sf.getId(),
                sf.getObjectKey(),
                null, // URL 按需生成
                sf.getContentType(),
                sf.getFileSize()
        );
        return Result.ok(dto);
    }

    /**
     * 内部上传（Engine 使用）。
     */
    @PostMapping(value = "/upload")
    public Result<UploadResult> upload(
            @RequestPart MultipartFile file,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        StorageFile sf = fileService.upload(userId, tenantId, file, "ASSET");

        UploadResult uploadResult = new UploadResult(
                sf.getId(),
                sf.getObjectKey(),
                null,
                sf.getFileSize()
        );
        return Result.ok(uploadResult);
    }

    /**
     * 内部删除。
     */
    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable String fileId) {
        fileService.delete(fileId);
        return Result.ok();
    }
}
