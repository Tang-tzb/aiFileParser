package com.aifp.aiagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建项目 DTO
 * <p>
 * 项目编号 {@code projectNo} 为业务唯一标识，创建时全库查重（uk_project_no 兜底）。
 *
 * @author Tang_tzb
 */
@Data
public class ProjectCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "项目编号不能为空")
    @Size(max = 64, message = "项目编号最长64字符")
    private String projectNo;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称最长100字符")
    private String projectName;

    @Size(max = 500, message = "项目描述最长500字符")
    private String description;
}
