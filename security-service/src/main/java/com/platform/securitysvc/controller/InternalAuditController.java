package com.platform.securitysvc.controller;

import com.platform.core.base.Result;
import com.platform.feign.dto.AuditResult;
import com.platform.securitysvc.service.ContentSafetyService;
import com.platform.securitysvc.service.PromptAuditService;
import com.platform.securitysvc.service.WatermarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 内部安全服务控制器 — /api/v1/internal/audit
 * 供 Agent-Core 和 Engine 通过 Feign 调用。
 */
@RestController
@RequestMapping("/api/v1/internal/audit")
@RequiredArgsConstructor
public class InternalAuditController {

    private final PromptAuditService promptAuditService;
    private final ContentSafetyService contentSafetyService;
    private final WatermarkService watermarkService;

    /**
     * Prompt 审核（Agent-Core 提交任务时调用）。
     */
    @PostMapping("/prompt")
    public Result<AuditResult> auditPrompt(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        AuditResult result = promptAuditService.audit(prompt);
        return Result.ok(result);
    }

    /**
     * 内容安全检测（生成完成后调用）。
     */
    @PostMapping("/content")
    public Result<AuditResult> auditContent(@RequestBody Map<String, String> body) {
        String fileUrl = body.get("fileUrl");
        String type = body.getOrDefault("type", "image");

        AuditResult result;
        if ("video".equalsIgnoreCase(type)) {
            result = contentSafetyService.checkVideo(fileUrl);
        } else {
            result = contentSafetyService.checkImage(fileUrl);
        }
        return Result.ok(result);
    }

    /**
     * 水印注入（Engine 生成后调用）。
     */
    @PostMapping(value = "/watermark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<byte[]> addWatermark(
            @RequestPart MultipartFile file,
            @RequestParam String userId) {
        try {
            byte[] watermarked = watermarkService.injectWatermark(file.getBytes(), userId);
            return Result.ok(watermarked);
        } catch (IOException e) {
            return Result.fail(5001, "水印注入失败: " + e.getMessage());
        }
    }
}
