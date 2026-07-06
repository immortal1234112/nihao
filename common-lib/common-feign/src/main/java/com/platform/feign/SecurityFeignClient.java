package com.platform.feign;

import com.platform.core.base.Result;
import com.platform.feign.dto.AuditResult;
import com.platform.feign.fallback.SecurityFeignFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "security-service", path = "/api/v1/internal/audit", fallback = SecurityFeignFallback.class)
public interface SecurityFeignClient {

    @PostMapping("/prompt")
    Result<AuditResult> auditPrompt(@RequestBody Map<String, String> body);

    @PostMapping("/content")
    Result<AuditResult> auditContent(@RequestBody Map<String, String> body);
}
