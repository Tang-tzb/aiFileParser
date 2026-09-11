package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.ProjectStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目详情展示 VO
 *
 * @author Tang_tzb
 */
@Data
public class ProjectVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 项目 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    /**
     * 项目编号
     */
    private String projectNo;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目描述
     */
    private String description;

    /**
     * 项目状态
     */
    private ProjectStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
