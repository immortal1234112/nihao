package com.platform.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解 —— 标记需要记录审计日志的方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型，如 "LOGIN", "CREATE_TASK", "DELETE_FILE" */
    String action();

    /** 操作详情，支持 SpEL 表达式，如 "'删除文件:' + #fileId" */
    String detail() default "";
}
