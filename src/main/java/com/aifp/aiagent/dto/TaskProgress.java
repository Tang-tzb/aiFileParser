package com.aifp.aiagent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 任务进度（SSE 事件载荷 + Redis 快照对象）
 * <p>
 * 贯穿 发布器→Redis→监听器→SSE 链路：
 * <ul>
 *   <li>0%  PARSING   解析中</li>
 *   <li>50% VECTORING 向量化中</li>
 *   <li>80% EXTRACTING AI 抽取中</li>
 *   <li>100% SUCCESS   完成（result 携带 {@link ExtractionResult}）</li>
 *   <li>-1   FAILED    处理失败</li>
 * </ul>
 *
 * @author Tang_tzb
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskProgress implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一标识
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
     * 阶段状态码：PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED
     */
    private String status;

    /**
     * 进度百分比（0/50/80/100，失败为 -1）
     */
    private int percent;

    /**
     * 人类可读描述
     */
    private String message;

    /**
     * 最终结果（仅 100% 时填充 {@link ExtractionResult}）
     */
    private Object result;

    /**
     * 事件时间戳（epoch millis）
     */
    private long timestamp;

    public TaskProgress() {
    }

    public TaskProgress(String taskId, Long fileId, Long formId, String status,
                        int percent, String message, Object result) {
        this.taskId = taskId;
        this.fileId = fileId;
        this.formId = formId;
        this.status = status;
        this.percent = percent;
        this.message = message;
        this.result = result;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 拷贝工厂：基于当前进度生成下一阶段进度，复用 taskId/fileId/formId。
     * 便于异步执行器链式发布，避免重复构造上下文。
     */
    public TaskProgress with(String status, int percent, String message, Object result) {
        return new TaskProgress(this.taskId, this.fileId, this.formId,
                status, percent, message, result);
    }

    /**
     * 是否终态（完成或失败）。
     * <p>
     * 派生计算属性，{@link JsonIgnore} 阻止其被 Jackson 当作 {@code terminal} 属性序列化，
     * 否则订阅端反序列化会因 {@code TaskProgress} 无该字段而抛 Unrecognized field。
     */
    @JsonIgnore
    public boolean isTerminal() {
        return percent == 100 || "FAILED".equals(status);
    }
}
