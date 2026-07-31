package com.aifp.aiagent.entity;

import com.aifp.aiagent.entity.enums.FieldType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单字段定义实体
 * <p>
 * 对应表 form_field_definition：表单下的字段定义。
 * {@code fieldCode} + {@code fieldType} + {@code description} 为后续 AI 抽取
 * Prompt 与 JSON Schema 生成的核心输入。
 *
 * @author aiFileParser
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("form_field_definition")
public class FormFieldDefinition extends BaseEntity {

    /**
     * 所属表单ID
     */
    private Long formId;

    /**
     * 字段名称（中文展示）
     */
    private String fieldName;

    /**
     * 字段编码（表单内唯一，用于结构化输出 key）
     */
    private String fieldCode;

    /**
     * 字段类型
     */
    private FieldType fieldType;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 字段描述（用于辅助 AI 理解抽取目标）
     */
    private String description;

    /**
     * 排序号（升序）
     */
    private Integer sort;
}
