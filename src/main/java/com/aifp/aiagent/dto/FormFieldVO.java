package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FieldType;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 表单字段展示 VO
 *
 * @author Tang_tzb
 */
@Data
public class FormFieldVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 字段 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fieldId;
    private String fieldName;
    private String fieldCode;
    private FieldType fieldType;
    private Boolean required;
    private String description;
    private Integer sort;
}
