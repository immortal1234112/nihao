package com.platform.mq.message;
import java.io.Serializable;
import java.util.List;

public record TaskResultMessage(String taskId, String engineType, String status,
                                List<FileInfo> outputFiles, String errorInfo,
                                long durationMs) implements Serializable {
    public record FileInfo(String fileId, String url, String thumbnailUrl) implements Serializable {}
}
