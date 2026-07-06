package com.platform.mq.message;
import java.io.Serializable;
import java.util.Map;

public record TaskMessage(String taskId, String userId, String tenantId, String workflowType,
                          String engineType, Map<String, Object> params, int stepOrder,
                          int retryCount) implements Serializable {
}
