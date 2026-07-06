package com.platform.mq.message;
import java.io.Serializable;
import java.time.LocalDateTime;

public record AuditLogMessage(String userId, String tenantId, String action, String detail,
                               String ip, LocalDateTime timestamp, String result)
        implements Serializable {
}
