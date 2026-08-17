package com.platform.feign.fallback;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.feign.StorageFeignClient;
import com.platform.feign.dto.FileDTO;
import com.platform.storage.UploadResult;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class StorageFeignFallback implements StorageFeignClient {
    @Override
    public Result<FileDTO> getFile(String fileId) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<UploadResult> upload(MultipartFile file, String userId, String tenantId) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<java.util.List<FileDTO>> listByTask(String taskId) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<java.util.List<FileDTO>> listByIds(java.util.List<String> ids) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<Void> delete(String fileId) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }
}
