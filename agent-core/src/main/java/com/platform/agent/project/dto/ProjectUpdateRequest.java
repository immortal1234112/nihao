package com.platform.agent.project.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 更新项目请求（全部可选）。
 */
@Data
public class ProjectUpdateRequest {

    private String name;

    private String coverAssetId;

    /** 前端 dayjs 格式为 yyyy-MM-dd HH:mm:ss（空格分隔），Jackson 默认 ISO 格式会解析失败 → 5099 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;
}
