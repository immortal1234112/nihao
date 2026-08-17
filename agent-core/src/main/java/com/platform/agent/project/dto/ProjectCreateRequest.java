package com.platform.agent.project.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建项目请求。
 */
@Data
public class ProjectCreateRequest {

    @NotBlank(message = "项目名称不能为空")
    private String name;

    /** 前端 dayjs 格式为 yyyy-MM-dd HH:mm:ss（空格分隔），Jackson 默认 ISO 格式会解析失败 → 5099 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;
}
