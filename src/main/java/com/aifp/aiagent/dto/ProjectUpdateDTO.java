package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.ProjectStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编辑项目 DTO
 * <p>
 * 全字段可选：非空字段才参与更新（null = 不修改）。
 * 项目编号 {@code projectNo} 不允许修改，故不在本 DTO 中。
 *
 * @author Tang_tzb
 */
@Data
public class ProjectUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Size(max = 100, message = "项目名称最长100字符")
    private String projectName;

    @Size(max = 500, message = "项目描述最长500字符")
    private String description;

    /**
     * 项目状态（ACTIVE/ARCHIVED，非法枚举值由 Jackson 反序列化拒绝）
     */
    private ProjectStatus status;
}
