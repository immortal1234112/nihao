package com.platform.securitysvc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 水印注入服务 —— 在生成图片右下角添加半透明文字水印。
 */
@Slf4j
@Service
public class WatermarkService {

    /**
     * 在图片上注入水印。
     *
     * @param imageBytes 原始图片字节
     * @param userId     用户 ID（用于水印文字）
     * @return 带水印的图片字节（PNG 格式）
     */
    public byte[] injectWatermark(byte[] imageBytes, String userId) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (original == null) {
            throw new IOException("无法解析图片数据");
        }

        int width = original.getWidth();
        int height = original.getHeight();

        // 创建带透明层的画布
        BufferedImage watermarked = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = watermarked.createGraphics();

        // 绘制原图
        g2d.drawImage(original, 0, 0, null);

        // 水印文字
        String watermarkText = "AI Generated | User:" + userId;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 自适应字体大小
        int fontSize = Math.max(12, width / 50);
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
        g2d.setFont(font);

        // 计算文字宽高
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(watermarkText);
        int textHeight = fm.getHeight();

        // 右下角定位（留 20px 边距）
        int x = width - textWidth - 20;
        int y = height - 20;

        // 半透明白色
        g2d.setColor(new Color(255, 255, 255, 80));
        g2d.drawString(watermarkText, x, y);

        // 再加一个略偏移的半透明黑色阴影增强可读性
        g2d.setColor(new Color(0, 0, 0, 60));
        g2d.drawString(watermarkText, x + 1, y + 1);

        g2d.dispose();

        // 输出 PNG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(watermarked, "png", baos);
        log.debug("Watermark injected: userId={}, size={}x{}", userId, width, height);

        return baos.toByteArray();
    }
}
