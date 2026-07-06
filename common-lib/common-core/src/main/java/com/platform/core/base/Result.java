package com.platform.core.base;

import com.platform.core.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应体。所有 Controller 返回值都包装为此类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 业务状态码，0 表示成功 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 响应数据 */
    private T data;
    /** 请求追踪 ID */
    private String requestId;
    /** 响应时间戳 */
    private long timestamp;

    // ==================== 成功响应 ====================

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    // ==================== 失败响应 ====================

    public static <T> Result<T> fail(ErrorCode error) {
        Result<T> r = new Result<>();
        r.code = error.getCode();
        r.message = error.getMessage();
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.timestamp = System.currentTimeMillis();
        return r;
    }
}
