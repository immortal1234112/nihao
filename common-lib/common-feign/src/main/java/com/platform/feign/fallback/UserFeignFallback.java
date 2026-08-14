package com.platform.feign.fallback;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.feign.UserFeignClient;
import com.platform.feign.dto.QuotaDTO;
import com.platform.feign.dto.UserDTO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UserFeignFallback implements UserFeignClient {
    @Override
    public Result<UserDTO> getUser(String userId) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<QuotaDTO> getQuota(String userId, String type) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<Boolean> consumeQuota(String userId, Map<String, Object> body) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<Boolean> compensateQuota(String userId, Map<String, Object> body) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }
}
