package com.platform.image.meme.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表情包生成请求 — 用户输入一句话。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemeGenerateRequest {

    @NotBlank(message = "文字不能为空")
    private String text;
}
