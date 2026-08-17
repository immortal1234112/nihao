package com.platform.agent.project.dto;

import lombok.Data;

import java.util.Map;

/**
 * 更新创作简报请求（全部可选，未传字段清空）。
 */
@Data
public class BriefUpdateRequest {

    private String targetAudience;

    private String theme;

    private String tone;

    private String channels;

    private String brandWords;

    private String bannedWords;

    private Map<String, Object> extra;
}
