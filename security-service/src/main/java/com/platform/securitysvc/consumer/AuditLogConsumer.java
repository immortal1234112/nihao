package com.platform.securitysvc.consumer;

import com.platform.mq.exchange.QueueConstants;
import com.platform.mq.message.AuditLogMessage;
import com.platform.securitysvc.entity.AuditLog;
import com.platform.securitysvc.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 审计日志消费者 —— 消费由 Gateway/各服务异步发送的审计日志，批量写入 MySQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {

    private final AuditLogMapper auditLogMapper;

    @RabbitListener(queues = QueueConstants.AUDIT_LOG_QUEUE, concurrency = "2-5")
    public void onMessage(AuditLogMessage message) {
        try {
            AuditLog entity = new AuditLog();
            entity.setUserId(message.userId());
            entity.setTenantId(message.tenantId());
            entity.setAction(message.action());
            entity.setDetail(message.detail());
            entity.setIp(message.ip());
            entity.setActionTime(message.timestamp());
            entity.setResult(message.result());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("Failed to persist audit log: {}", e.getMessage());
        }
    }
}
