package com.platform.storagesvc.controller;

import com.platform.core.base.Result;
import com.platform.feign.dto.FileDTO;
import com.platform.storage.UploadResult;
import com.platform.storagesvc.entity.StorageFile;
import com.platform.storagesvc.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 内部 Feign 调用控制器 — /api/v1/internal/files
 * 供 Image-Engine / Video-Engine 等内部服务调用。
 */
@RestController
@RequestMapping("/api/v1/internal/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileService fileService;

    /** 限时公开取图 URL 生成器（参考图注入用） */
    private final PublicFileController publicFileController;

    /** 网关地址：拼装对外可访问的下载 URL（客户端经网关下载，鉴权由网关 JWT 完成） */
    @Value("${storage.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    /**
     * 获取文件信息。
     */
    @GetMapping("/{fileId}")
    public Result<FileDTO> getFile(@PathVariable String fileId) {
        StorageFile sf = fileService.getById(fileId);
        FileDTO dto = new FileDTO(
                sf.getId(),
                sf.getObjectKey(),
                publicFileUrl(sf.getId()),
                sf.getContentType(),
                sf.getFileSize(),
                sf.getWidth(),
                sf.getHeight(),
                sf.getDurationMs()
        );
        return Result.ok(dto);
    }

    /**
     * 生成限时公开取图 URL（Agent-Core 参考图注入用）。
     * 返回带 HMAC 签名的免鉴权链接，供云端模型 provider 拉取参考图，10 分钟过期。
     */
    @GetMapping("/{fileId}/public-url")
    public Result<String> buildPublicUrl(@PathVariable String fileId) {
        StorageFile sf = fileService.getById(fileId);
        if (sf == null) {
            throw new com.platform.core.exception.BizException(
                    com.platform.core.exception.ErrorCode.FILE_NOT_FOUND);
        }
        long expires = publicFileController.defaultExpires();
        return Result.ok(publicFileController.buildPublicUrl(fileId, expires));
    }

    /**
     * 内部取文件字节（Agent-Core 任务结果下载代理用，服务间免公开鉴权）。
     */
    @GetMapping(value = "/{fileId}/content", produces = "application/octet-stream")
    public ResponseEntity<byte[]> downloadContent(@PathVariable String fileId) {
        StorageFile sf = fileService.getById(fileId);
        if (sf == null) {
            throw new com.platform.core.exception.BizException(
                    com.platform.core.exception.ErrorCode.STORAGE_ERROR, "文件不存在: " + fileId);
        }
        try (java.io.InputStream in = fileService.download(fileId).getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            sf.getContentType() != null ? sf.getContentType() : "application/octet-stream"))
                    .body(in.readAllBytes());
        } catch (Exception e) {
            throw new com.platform.core.exception.BizException(
                    com.platform.core.exception.ErrorCode.STORAGE_ERROR, "文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 按任务 ID 查询产出文件（供 Agent-Core 资产归档调用）。
     */
    @GetMapping("/by-task/{taskId}")
    public Result<List<FileDTO>> listByTask(@PathVariable String taskId) {
        List<FileDTO> files = fileService.listByTask(taskId).stream()
                .map(sf -> new FileDTO(
                        sf.getId(),
                        sf.getObjectKey(),
                        publicFileUrl(sf.getId()),
                        sf.getContentType(),
                        sf.getFileSize(),
                        sf.getWidth(),
                        sf.getHeight(),
                        sf.getDurationMs()))
                .toList();
        return Result.ok(files);
    }

    /**
     * 批量查询文件（供 Agent-Core 资产列表预览调用）。
     */
    @GetMapping("/batch")
    public Result<List<FileDTO>> listByIds(@RequestParam List<String> ids) {
        List<FileDTO> files = fileService.listByIds(ids).stream()
                .map(sf -> new FileDTO(
                        sf.getId(),
                        sf.getObjectKey(),
                        publicFileUrl(sf.getId()),
                        sf.getContentType(),
                        sf.getFileSize(),
                        sf.getWidth(),
                        sf.getHeight(),
                        sf.getDurationMs()))
                .toList();
        return Result.ok(files);
    }

    /**
     * 内部上传（Engine 使用）—— 返回真实下载 URL（此前为 null，导致前端拿不到文件）。
     * X-Source-Task-Id 可选：Engine 生成结果带 taskId 上传，供 Agent-Core 归档时按任务反查文件。
     */
    @PostMapping(value = "/upload")
    public Result<UploadResult> upload(
            @RequestPart MultipartFile file,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-Source-Task-Id", required = false) String taskId) {

        StorageFile sf = fileService.upload(userId, tenantId, file, "ASSET", taskId);

        UploadResult uploadResult = new UploadResult(
                sf.getId(),
                sf.getObjectKey(),
                publicFileUrl(sf.getId()),
                sf.getFileSize()
        );
        return Result.ok(uploadResult);
    }

    private String publicFileUrl(String fileId) {
        return publicBaseUrl + "/api/v1/files/" + fileId + "/download";
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
