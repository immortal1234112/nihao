package com.platform.core.exception;

import lombok.Getter;

/**
 * 业务异常，所有业务层异常统一使用此类或其子类抛出。
 * GlobalExceptionHandler 会统一拦截并转换为 Result 响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode error) {
        super(error.getMessage());
        this.code = error.getCode();
    }

    public BizException(ErrorCode error, String detail) {
        super(detail);
        this.code = error.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
