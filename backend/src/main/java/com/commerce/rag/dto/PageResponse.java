package com.commerce.rag.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;

/**
 * 分页响应包装类。
 *
 * @param records 当前页数据列表
 * @param total   总记录数
 * @param page    当前页码（1-based）
 * @param size    每页条数
 * @param <T>     数据类型
 */
public record PageResponse<T>(List<T> records, long total, int page, int size) {

    /**
     * 从 MyBatis-Plus IPage 构造分页响应。
     */
    public static <T> PageResponse<T> of(IPage<T> page) {
        return new PageResponse<>(page.getRecords(), page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }
}
