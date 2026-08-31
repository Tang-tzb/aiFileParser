package com.aifp.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 通用分页返回结果
 * <p>
 * 与 ORM 层 {@code IPage} 解耦：Service 层将 IPage 转换为本对象返回，
 * 避免 MyBatis-Plus 类型泄漏到 REST 接口，便于序列化控制与未来切换数据源。
 *
 * @param <T> 记录类型
 * @author Tang_tzb
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 总页数
     */
    private long pages;

    /**
     * 当前页码
     */
    private long current;

    /**
     * 每页大小
     */
    private long size;

    /**
     * 当前页数据
     */
    private List<T> records = Collections.emptyList();

    /**
     * 由 MyBatis-Plus IPage 字段构造（推荐入口）
     */
    public static <T> PageResult<T> of(long total, long pages, long current, long size, List<T> records) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setPages(pages);
        r.setCurrent(current);
        r.setSize(size);
        r.setRecords(records == null ? Collections.emptyList() : records);
        return r;
    }

    /**
     * 空结果快捷构造
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(0L, 0L, 0L, 0L, Collections.emptyList());
    }
}
