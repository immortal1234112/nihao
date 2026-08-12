package com.platform.image.meme.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 中存储的任务状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemeTaskState {

    private String status;      // PENDING / PROCESSING / DONE / FAILED
    private String userId;      // 任务归属用户（防越权查询）
    private String imageUrl;
    private String memeText;
    private String errorInfo;
    private Long createdAt;

    public enum Status {
        PENDING, PROCESSING, DONE, FAILED
    }
}
