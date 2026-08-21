package com.aifp.aiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 字段级抽取错误
 * <p>
 * 由 {@link com.aifp.aiagent.rag.FieldSchemaValidator} 产出，供 Retry 反馈与
 * {@link ExtractionResult} 持有，对调用方透明暴露字段级可靠性信息。
 *
 * @author Tang_tzb
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldError implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 错误类型：MISSING(缺失) / TYPE(类型错误) / FORMAT(格式错误)
     */
    private String errorType;

    /**
     * 错误描述
     */
    private String message;

    /**
     * 原始违规值（便于反馈 AI 与排查）
     */
    private Object rawValue;
}
