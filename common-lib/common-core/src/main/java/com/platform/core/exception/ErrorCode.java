package com.platform.core.exception;

import lombok.Getter;

/**
 * 统一错误码枚举。
 * 区间划分:
 *   0     - 成功
 *   1xxx  - 参数错误
 *   2xxx  - 认证授权
 *   3xxx  - 业务限制
 *   4xxx  - 资源不存在
 *   5xxx  - 系统错误
 *   9xxx  - 安全拦截
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // 1xxx: 参数错误
    PARAM_INVALID(1001, "参数校验失败"),
    PARAM_MISSING(1002, "缺少必填参数"),
    PARAM_FORMAT(1003, "参数格式错误"),

    // 2xxx: 认证授权
    TOKEN_EXPIRED(2001, "Token 已过期"),
    TOKEN_INVALID(2002, "Token 无效"),
    NO_PERMISSION(2003, "无操作权限"),
    API_KEY_INVALID(2004, "API Key 无效"),
    API_KEY_EXPIRED(2005, "API Key 已过期"),

    // 3xxx: 业务限制
    QUOTA_EXCEEDED(3001, "配额已用尽"),
    CONCURRENCY_LIMIT(3002, "并发任务数超限"),
    RATE_LIMITED(3003, "请求频率超限"),
    TENANT_SUSPENDED(3004, "租户已停用"),
    LAST_OWNER_PROTECTED(3005, "项目至少保留一名 OWNER"),
    MEMBER_NOT_IN_TENANT(3006, "用户不是租户成员"),

    // 4xxx: 资源不存在
    TASK_NOT_FOUND(4001, "任务不存在"),
    FILE_NOT_FOUND(4002, "文件不存在"),
    USER_NOT_FOUND(4003, "用户不存在"),
    DATASET_NOT_FOUND(4004, "知识库不存在"),
    DOCUMENT_NOT_FOUND(4005, "文档不存在"),
    MEMORY_NOT_FOUND(4006, "记忆不存在"),
    TEMPLATE_NOT_FOUND(4007, "模板不存在"),
    PROJECT_NOT_FOUND(4008, "项目不存在"),
    ASSET_NOT_FOUND(4009, "资产不存在"),

    // 5xxx: 系统错误
    SERVICE_TIMEOUT(5001, "服务调用超时"),
    MODEL_UNAVAILABLE(5002, "模型暂不可用"),
    STORAGE_ERROR(5003, "存储服务异常"),
    AUDIT_UNAVAILABLE(5004, "审核服务暂不可用，请稍后重试"),
    QUOTA_UNAVAILABLE(5005, "配额服务暂不可用，请稍后重试"),
    INTERNAL_ERROR(5099, "系统内部错误"),

    // 9xxx: 安全拦截
    CONTENT_BLOCKED(9001, "内容审核未通过"),
    SENSITIVE_PROMPT(9002, "Prompt 包含敏感词");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
