package com.aifp.aiagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 创建表单 DTO
 * <p>
 * 接收表单头 + 可选字段列表。当 fields 非空时，与表单头在同一事务内批量插入。
 *
 * @author aiFileParser
 */
@Data
public class FormCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "表单名称不能为空")
    @Size(max = 100, message = "表单名称最长100字符")
    private String formName;

    @Size(max = 500, message = "表单描述最长500字符")
    private String description;

    /**
     * 可选：创建表单时一并写入的字段列表
     */
    @Valid
    private List<FormFieldCreateDTO> fields;
}
