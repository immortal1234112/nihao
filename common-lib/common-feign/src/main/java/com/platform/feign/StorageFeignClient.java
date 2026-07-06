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

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Result<UploadResult> upload(@RequestPart("file") MultipartFile file);

    @DeleteMapping("/{fileId}")
    Result<Void> delete(@PathVariable("fileId") String fileId);
}
