package com.platform.mq.exchange;

public final class QueueConstants {
    private QueueConstants() {}

    public static final String IMAGE_TASK_QUEUE = "image.task.queue";
    public static final String VIDEO_TASK_QUEUE = "video.task.queue";
    public static final String TASK_RESULT_QUEUE = "task.result.queue";
    public static final String AUDIT_LOG_QUEUE = "audit.log.queue";
    public static final String TASK_DLQ = "task.dead.letter.queue";
}
