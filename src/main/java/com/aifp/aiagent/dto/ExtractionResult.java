package com.aifp.aiagent.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * AI 字段抽取可靠性结果
 * <p>
 * 终点「Entity」表现为类型化结果 DTO：含校验/转换后的类型化值、剩余字段错误、LLM 调用次数。
 * 对外由 {@link com.aifp.aiagent.controller.FillController} 返回，暴露可靠性元数据。
 *
 * @author Tang_tzb
 */
@Data
public class ExtractionResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 类型化字段值，key=fieldCode，value 已按 FieldType 转换
     */
    private Map<String, Object> values;

    /**
     * 剩余字段级错误（Retry 耗尽后仍存在的）
     */
    private List<FieldError> errors;

    /**
     * LLM 调用次数（含首次 + 重试）
     */
    private int attemptsUsed;
}
