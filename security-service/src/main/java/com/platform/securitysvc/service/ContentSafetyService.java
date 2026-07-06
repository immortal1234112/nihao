package com.platform.securitysvc.service;

import com.platform.feign.dto.AuditResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 内容安全检测服务 —— 对生成后的图片/视频进行 NSFW 检测。
 * 当前为预留桩实现，后续可接入第三方内容审核 API。
 */
@Slf4j
@Service
public class ContentSafetyService {

    /**
     * 检测图片内容安全。
     */
    public AuditResult checkImage(String fileUrl) {
        log.debug("Content safety check for image: {} (stub - passed)", fileUrl);
        // TODO: 接入 NSFW 图片检测 API
        return new AuditResult(true, "通过（桩）");
    }

    /**
     * 检测视频内容安全。
     */
    public AuditResult checkVideo(String fileUrl) {
        log.debug("Content safety check for video: {} (stub - passed)", fileUrl);
        // TODO: 接入 NSFW 视频检测 API
        return new AuditResult(true, "通过（桩）");
    }
}
