package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步任务启动结果
 * <p>
 * 返回 taskId 供前端订阅 SSE 进度：{@code GET /task/progress/{taskId}}。
 *
 * @author Tang_tzb
 */
@Data
@AllArgsConstructor
public class TaskStartVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一标识（UUID，用于 SSE 订阅与 Redis 进度查询）
     */
    private String taskId;

    /**
     * 关联文件 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    /**
     * 关联表单 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long formId;

    /**
     * 任务创建时间
     */
    private LocalDateTime createTime;
}
