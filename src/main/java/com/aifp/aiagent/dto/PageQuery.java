package com.aifp.aiagent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通用分页查询参数
 * <p>
 * 所有分页接口共享基类：子类可扩展过滤条件（如 FileType/status），
 * pageNum/pageSize 由此基类统一约束。
 *
 * @author Tang_tzb
 */
@Data
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 页码，从 1 开始
     */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /**
     * 每页大小，1~100
     */
    @Min(value = 1, message = "每页大小不能小于 1")
    @Max(value = 100, message = "每页大小不能超过 100")
    private Integer pageSize = 10;
}
