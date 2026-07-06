package com.platform.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 意图解析器 —— 从用户 Prompt 中提取关键词，推断图片类型、风格、比例、色调。
 */
@Slf4j
@Component
public class IntentParser {

    public Map<String, String> parse(String prompt) {
        Map<String, String> intent = new LinkedHashMap<>();
        String lower = prompt.toLowerCase();

        // 类型推断
        if (containsAny(lower, "poster", "海报", "banner")) {
            intent.put("type", "poster");
        } else if (containsAny(lower, "illustration", "插画", "插圖", "drawing")) {
            intent.put("type", "illustration");
        } else if (containsAny(lower, "photo", "照片", "写实", "寫真", "realistic", "photorealistic")) {
            intent.put("type", "photo");
        } else if (containsAny(lower, "logo", "图标", "icon", "標誌")) {
            intent.put("type", "logo");
        } else {
            intent.put("type", "illustration");
        }

        // 风格推断
        if (containsAny(lower, "cyberpunk", "赛博朋克", "赛博")) {
            intent.put("style", "cyberpunk");
        } else if (containsAny(lower, "anime", "动漫", "二次元", "manga")) {
            intent.put("style", "anime");
        } else if (containsAny(lower, "photorealistic", "写实", "照片级")) {
            intent.put("style", "photorealistic");
        } else if (containsAny(lower, "oil painting", "油画", "古典")) {
            intent.put("style", "oil-painting");
        } else if (containsAny(lower, "watercolor", "水彩")) {
            intent.put("style", "watercolor");
        } else if (containsAny(lower, "sketch", "素描", "线稿")) {
            intent.put("style", "sketch");
        } else if (containsAny(lower, "pixel", "像素")) {
            intent.put("style", "pixel-art");
        } else if (containsAny(lower, "minimalist", "极简", "简约")) {
            intent.put("style", "minimalist");
        } else {
            intent.put("style", "default");
        }

        // 比例推断
        if (containsAny(lower, "portrait", "肖像", "竖版", "vertical", "人像")) {
            intent.put("aspectRatio", "portrait");
        } else if (containsAny(lower, "landscape", "风景", "横版", "horizontal", "风光")) {
            intent.put("aspectRatio", "landscape");
        } else if (containsAny(lower, "square", "方形", "1:1")) {
            intent.put("aspectRatio", "square");
        } else {
            intent.put("aspectRatio", "square");
        }

        // 色调推断
        if (containsAny(lower, "dark", "暗黑", "黑暗", "黑夜", "noir")) {
            intent.put("tone", "dark");
        } else if (containsAny(lower, "bright", "明亮", "阳光", "清新")) {
            intent.put("tone", "bright");
        } else if (containsAny(lower, "warm", "暖色", "温暖", "温馨")) {
            intent.put("tone", "warm");
        } else if (containsAny(lower, "cool", "冷色", "冷色调", "冰冷")) {
            intent.put("tone", "cool");
        } else {
            intent.put("tone", "neutral");
        }

        intent.put("theme", prompt);
        log.debug("Intent parsed: {}", intent);
        return intent;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
