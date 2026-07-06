package com.platform.core.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> records;

    /** 分页信息 */
    private PageInfo pagination;

    /**
     * 分页信息
     */
    public record PageInfo(int page, int size, long total, int totalPages) {}

    /**
     * 便捷构建方法
     */
    public static <T> PageResult<T> of(List<T> records, int page, int size, long total) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return new PageResult<>(records, new PageInfo(page, size, total, totalPages));
    }
}
