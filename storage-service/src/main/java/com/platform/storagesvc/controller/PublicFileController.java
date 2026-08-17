package com.platform.storagesvc.controller;

import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.storagesvc.entity.StorageFile;
import com.platform.storagesvc.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 公开临时取图端点 — /api/v1/public/files
 * <p>
 * 供云端模型 provider（agnes 图生图/多图合成、智谱图生视频等）主动拉取参考图：
 * 它们没有平台登录凭证，且服务器不持本地地址，必须给一个"公网可达 + 免鉴权"的
 * 短时有效 URL。安全模型：
 * <ul>
 *   <li>签名 = HMAC-SHA256(internal.auth-token, fileId|expires)，密钥不落 URL；</li>
 *   <li>expires 为 Unix 秒时间戳，过期即拒绝（默认 10 分钟 TTL）；</li>
 *   <li>fileId 为雪花 ID 不可枚举，配合签名杜绝伪造/批量拉取。</li>
 * </ul>
 * 网关白名单放行 /api/v1/public/**（跳过 JWT），生产部署须将 storage.public-base-url
 * 配为公网 https 域名，否则生成的 URL 不可达。
 */
@RestController
@RequestMapping("/api/v1/public/files")
@RequiredArgsConstructor
public class PublicFileController {

    /** 临时链接有效期（秒）：覆盖模型 provider 拿到 URL 到拉取完成的时间窗 */
    private static final long TTL_SECONDS = 600;

    private final FileService fileService;

    /** 签名密钥：与内部接口令牌同源（start.ps1 生成的 INTERNAL_AUTH_TOKEN） */
    @Value("${internal.auth-token:}")
    private String authToken;

    /**
     * 验签取文件字节。签名不匹配 / 过期 / 未配置密钥 → 401。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable String fileId,
                                           @RequestParam long expires,
                                           @RequestParam String sig) {
        if (authToken == null || authToken.isBlank()) {
            throw new BizException(ErrorCode.NO_PERMISSION, "公开取图未配置签名密钥");
        }
        if (expires < System.currentTimeMillis() / 1000L) {
            throw new BizException(ErrorCode.NO_PERMISSION, "取图链接已过期");
        }
        // 恒定时间比较，避免时序侧信道
        byte[] expected = sign(fileId, expires);
        byte[] actual = sig.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new BizException(ErrorCode.NO_PERMISSION, "取图链接签名无效");
        }

        StorageFile sf = fileService.getById(fileId);
        if (sf == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        try (java.io.InputStream in = fileService.download(fileId).getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            sf.getContentType() != null ? sf.getContentType() : "application/octet-stream"))
                    .body(in.readAllBytes());
        } catch (Exception e) {
            throw new BizException(ErrorCode.STORAGE_ERROR, "文件读取失败: " + e.getMessage());
        }
    }

    /** 生成临时取图签名 URL（供 Agent-Core 注入生成参数；须经网关公网可达） */
    public String buildPublicUrl(String fileId, long expires) {
        return buildPublicUrl(fileId, expires, new String(sign(fileId, expires), StandardCharsets.US_ASCII));
    }

    public String buildPublicUrl(String fileId, long expires, String sig) {
        // 引擎 base：与前端展示 base 分离——公网可达的地址（内网穿透/部署域名），
        // 否则云端模型 provider 无法拉取参考图
        return engineBaseUrl + "/api/v1/public/files/" + fileId
                + "?expires=" + expires + "&sig=" + sig;
    }

    public long defaultExpires() {
        return System.currentTimeMillis() / 1000L + TTL_SECONDS;
    }

    private byte[] sign(String fileId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal((fileId + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac).getBytes(StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new IllegalStateException("取图签名生成失败", e);
        }
    }

    @Value("${storage.engine-base-url:http://localhost:8080}")
    private String engineBaseUrl;
}
