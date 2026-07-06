package com.platform.image.service;

import com.platform.image.model.GenerationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 后处理器 —— 格式归一化、尺寸调整、生成缩略图。
 */
@Slf4j
@Component
public class PostProcessor {

    private static final int MAX_WIDTH = 2048;
    private static final int MAX_HEIGHT = 2048;
    private static final int THUMBNAIL_SIZE = 200;

    /**
     * 后处理生成结果列表。
     */
    public List<GenerationResult> process(List<GenerationResult> results) {
        List<GenerationResult> processed = new ArrayList<>();
        for (GenerationResult result : results) {
            try {
                processed.add(processOne(result));
            } catch (Exception e) {
                log.warn("Post-processing failed for result: {}", e.getMessage());
                processed.add(result); // 返回原始结果
            }
        }
        return processed;
    }

    private GenerationResult processOne(GenerationResult result) throws Exception {
        byte[] imageBytes;

        // 获取图片字节
        if (result.getBase64Data() != null && !result.getBase64Data().isEmpty()) {
            imageBytes = Base64.getDecoder().decode(result.getBase64Data());
        } else if (result.getUrl() != null) {
            // URL 类型不便于本地处理，跳过
            return result;
        } else {
            return result;
        }

        // 尝试读取图片
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) return result;

        int width = image.getWidth();
        int height = image.getHeight();

        // 如果超出最大尺寸，等比缩放
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            double scale = Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height);
            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            result.setBase64Data(Base64.getEncoder().encodeToString(baos.toByteArray()));
            result.setFormat("png");
            result.setWidth(newWidth);
            result.setHeight(newHeight);
        }

        return result;
    }

    /**
     * 生成缩略图（Base64）。
     */
    public String generateThumbnail(GenerationResult result) {
        try {
            byte[] imageBytes;
            if (result.getBase64Data() != null && !result.getBase64Data().isEmpty()) {
                imageBytes = Base64.getDecoder().decode(result.getBase64Data());
            } else {
                return null;
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) return null;

            double scale = Math.min((double) THUMBNAIL_SIZE / image.getWidth(),
                    (double) THUMBNAIL_SIZE / image.getHeight());
            int tw = (int) (image.getWidth() * scale);
            int th = (int) (image.getHeight() * scale);

            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumb.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(image, 0, 0, tw, th, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.warn("Thumbnail generation failed: {}", e.getMessage());
            return null;
        }
    }
}
