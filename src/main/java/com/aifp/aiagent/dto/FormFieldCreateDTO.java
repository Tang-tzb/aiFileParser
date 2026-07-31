package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单个字段创建 DTO
 * <p>
 * 用于 create-form 的可选字段列表，以及 add-field 接口入参。
 * {@code fieldCode} 约束为合法标识符，便于后续作为 JSON key 与 Java 属性名。
 *
 * @author aiFileParser
 */
@Data
public class FormFieldCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "字段名称不能为空")
    @Size(max = 100, message = "字段名称最长100字符")
    private String fieldName;

    @NotBlank(message = "字段编码不能为空")
    @Size(max = 64, message = "字段编码最长64字符")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "字段编码须以字母开头且仅含字母数字下划线")
    private String fieldCode;

    @NotNull(message = "字段类型不能为空")
    private FieldType fieldType;

    /**
     * 是否必填，缺省视为 false
     */
    private Boolean required;

    @Size(max = 500, message = "字段描述最长500字符")
    private String description;

    /**
     * 排序号，缺省 0
     */
    private Integer sort;
}
