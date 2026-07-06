package com.platform.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTaskStatus {
    public enum State { PENDING, PROCESSING, DONE, FAILED }
    private State state;
    private int progress;
    private String errorInfo;
}
