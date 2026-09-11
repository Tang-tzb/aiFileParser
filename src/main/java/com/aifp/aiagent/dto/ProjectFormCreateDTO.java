package com.aifp.aiagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建项目表单实例 DTO（将表单定义绑定到项目）
 *
 * @author Tang_tzb
 */
@Data
public class ProjectFormCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表单定义ID（form_definition）
     */
    @NotNull(message = "表单ID不能为空")
    private Long formId;
}
