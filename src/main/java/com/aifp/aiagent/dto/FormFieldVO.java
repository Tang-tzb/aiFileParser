package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FieldType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 表单字段展示 VO
 *
 * @author aiFileParser
 */
@Data
public class FormFieldVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long fieldId;
    private String fieldName;
    private String fieldCode;
    private FieldType fieldType;
    private Boolean required;
    private String description;
    private Integer sort;
}
