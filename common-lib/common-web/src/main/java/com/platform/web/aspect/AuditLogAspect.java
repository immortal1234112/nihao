package com.platform.web.aspect;

import com.platform.web.annotation.AuditLog;
import com.platform.web.context.UserContext;
import com.platform.web.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志切面 —— 环绕通知记录方法调用信息。
 * 如果 RabbitTemplate 可用则异步发送到 MQ，否则仅打印日志。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    @Autowired(required = false)
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        String userId = UserContext.getUserId();
        String tenantId = TenantContext.get();
        String action = auditLog.action();
        String detail = auditLog.detail();

        String result = "SUCCESS";
        try {
            Object ret = joinPoint.proceed();
            return ret;
        } catch (Throwable e) {
            result = "FAIL";
            throw e;
        } finally {
            long elapsed = System.currentTimeMillis() - start;

            // 构建审计消息
            AuditInfo info = new AuditInfo(userId, tenantId, action, detail,
                    LocalDateTime.now(), result, elapsed);

            if (rabbitTemplate != null) {
                try {
                    rabbitTemplate.convertAndSend(
                            "platform.audit.exchange", "audit.log.info", info);
                } catch (Exception e) {
                    log.warn("Failed to send audit log to MQ: {}", e.getMessage());
                }
            }
            log.info("AUDIT: user={}, action={}, detail={}, result={}, elapsed={}ms",
                    userId, action, detail, result, elapsed);
        }
    }

    /**
     * 审计信息载体
     */
    public record AuditInfo(String userId, String tenantId, String action, String detail,
                            LocalDateTime timestamp, String result, long elapsedMs) {
    }
}
