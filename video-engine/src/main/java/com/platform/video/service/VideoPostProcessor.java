package com.platform.video.service;

import com.platform.video.model.VideoGenerationResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 视频后处理器 —— 抽帧缩略图、本地下载、格式转码等。
 */
@Slf4j
@Component
public class VideoPostProcessor {

    private final OkHttpClient httpClient;

    @Value("${platform.video.download-dir:./videos}")
    private String downloadDir;

    @Value("${platform.video.download-enabled:false}")
    private boolean downloadEnabled;

    public VideoPostProcessor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS) // 视频可能很大
                .followRedirects(true)
                .build();
    }

    /**
     * 后处理视频结果 —— 可选本地下载 + 缩略图。
     */
    public VideoGenerationResult process(VideoGenerationResult result) {
        if (result.getVideoUrl() == null) {
            return result;
        }

        // 可选本地下载（默认关闭）。绝不把 file:// 路径写回 videoUrl —— 引擎本地磁盘客户端不可访问，
        // 写回会导致前端拿到不可用的 file:// URL
        if (downloadEnabled) {
            Path localPath = downloadToLocal(result);
            if (localPath != null) {
                log.info("Video saved to local: {}", localPath.toAbsolutePath());
            }
        }

        // 生成缩略图 URL（预留：应抽第一帧）
        if (result.getThumbnailUrl() == null) {
            result.setThumbnailUrl(result.getVideoUrl() + "?thumb=1");
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 下载视频到本地磁盘，返回保存的文件路径。
     */
    private Path downloadToLocal(VideoGenerationResult result) {
        String videoUrl = result.getVideoUrl();

        try {
            // 确保目录存在
            Path dir = Paths.get(downloadDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 生成文件名：时间戳_模型_uuid.扩展名
            String ext = result.getFormat() != null ? result.getFormat() : "mp4";
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String uid = UUID.randomUUID().toString().substring(0, 8);
            String filename = String.format("video_%s_%s.%s", timestamp, uid, ext);
            Path targetPath = dir.resolve(filename);

            if (videoUrl.startsWith("data:")) {
                // base64 data URL（来自本地 CogVideoX 等）
                saveBase64(videoUrl, targetPath);
            } else if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
                // 远程 URL（来自 Runway / Kling / CogVideoX-Flash 等）
                saveFromUrl(videoUrl, targetPath);
            } else {
                log.warn("Unsupported video URL scheme, skip local save: {}", videoUrl);
                return null;
            }

            // 更新文件大小
            long fileSize = Files.size(targetPath);
            result.setFileSize(fileSize);
            log.info("Video downloaded: {} ({} bytes)", targetPath.getFileName(), fileSize);

            return targetPath;

        } catch (Exception e) {
            log.error("Failed to download video to local: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 data URL 解码 base64 并写入文件。
     */
    private void saveBase64(String dataUrl, Path targetPath) throws IOException {
        // 格式: data:video/mp4;base64,AAAA...
        String base64Data;
        int commaIdx = dataUrl.indexOf(',');
        if (commaIdx > 0) {
            base64Data = dataUrl.substring(commaIdx + 1);
        } else {
            base64Data = dataUrl;
        }

        byte[] decoded = Base64.getDecoder().decode(base64Data);
        Files.write(targetPath, decoded);
        log.debug("Saved base64 video: {} bytes → {}", decoded.length, targetPath.getFileName());
    }

    /**
     * 从远程 URL 下载视频并写入文件（纯 Java I/O，无额外依赖）。
     */
    private void saveFromUrl(String videoUrl, Path targetPath) throws IOException {
        Request request = new Request.Builder()
                .url(videoUrl)
                .get()
                .header("User-Agent", "VideoEngine/1.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }

            var body = response.body();
            if (body == null) {
                throw new IOException("Empty response body");
            }

            // 使用 InputStream → Files.copy，避免 okio 依赖
            try (var input = body.byteStream()) {
                Files.copy(input, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
