package com.platform.feign;

import com.platform.core.base.Result;
import com.platform.feign.dto.FileDTO;
import com.platform.feign.fallback.StorageFeignFallback;
import com.platform.storage.UploadResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@FeignClient(name = "storage-service", path = "/api/v1/internal/files", fallback = StorageFeignFallback.class)
public interface StorageFeignClient {

    @GetMapping("/{fileId}")
    Result<FileDTO> getFile(@PathVariable("fileId") String fileId);

    /**
     * 生成限时公开取图 URL（参考图注入用）。
     * 返回带 HMAC 签名的免鉴权链接：10 分钟过期、只能下载该文件、防伪造。
     */
    @GetMapping("/{fileId}/public-url")
    Result<String> getPublicUrl(@PathVariable("fileId") String fileId);

    /** 内部取文件字节（任务结果下载代理用，服务间免公开鉴权） */
    @GetMapping(value = "/{fileId}/content", produces = "application/octet-stream")
    byte[] downloadContent(@PathVariable("fileId") String fileId);

    @GetMapping("/by-task/{taskId}")
    Result<java.util.List<FileDTO>> listByTask(@PathVariable("taskId") String taskId);

    @GetMapping("/batch")
    Result<java.util.List<FileDTO>> listByIds(@RequestParam("ids") java.util.List<String> ids);

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<UploadResult> upload(@RequestPart("file") MultipartFile file,
                                @RequestHeader("X-User-Id") String userId,
                                @RequestHeader("X-Tenant-Id") String tenantId,
                                @RequestHeader(value = "X-Source-Task-Id", required = false) String taskId);

    @DeleteMapping("/{fileId}")
    Result<Void> delete(@PathVariable("fileId") String fileId);
}
