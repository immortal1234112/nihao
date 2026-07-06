package com.platform.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步任务状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatus {
    public enum State { PENDING, PROCESSING, DONE, FAILED }

    private State state;
    private int progress; // 0-100
    private String errorInfo;
}
