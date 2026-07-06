package com.platform.feign.fallback;

import com.platform.core.base.Result;
import com.platform.core.exception.ErrorCode;
import com.platform.feign.SecurityFeignClient;
import com.platform.feign.dto.AuditResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SecurityFeignFallback implements SecurityFeignClient {
    @Override
    public Result<AuditResult> auditPrompt(Map<String, String> body) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }

    @Override
    public Result<AuditResult> auditContent(Map<String, String> body) {
        return Result.fail(ErrorCode.SERVICE_TIMEOUT);
    }
}
