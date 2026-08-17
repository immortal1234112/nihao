package com.platform.feign;

import com.platform.core.base.Result;
import com.platform.feign.dto.QuotaDTO;
import com.platform.feign.dto.UserDTO;
import com.platform.feign.fallback.UserFeignFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "user-service", path = "/api/v1/internal/users", fallback = UserFeignFallback.class)
public interface UserFeignClient {

    @GetMapping("/{userId}")
    Result<UserDTO> getUser(@PathVariable("userId") String userId);

    @GetMapping("/{userId}/quota")
    Result<QuotaDTO> getQuota(@PathVariable("userId") String userId, @RequestParam("type") String type);

    @GetMapping("/{userId}/tenant-membership")
    Result<Boolean> isTenantMember(@PathVariable("userId") String userId, @RequestParam("tenantId") String tenantId);

    @PostMapping("/{userId}/quota/consume")
    Result<Boolean> consumeQuota(@PathVariable("userId") String userId, @RequestBody Map<String, Object> body);

    @PostMapping("/{userId}/quota/compensate")
    Result<Boolean> compensateQuota(@PathVariable("userId") String userId, @RequestBody Map<String, Object> body);
}
