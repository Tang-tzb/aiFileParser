package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目表单实例 VO
 * <p>
 * {@code formName} 为冗余展示字段，由表单定义实时查询填充；
 * 字段定义明细复用既有 {@code GET /form/{formId}}，不在本 VO 内嵌。
 *
 * @author Tang_tzb
 */
@Data
public class ProjectFormVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 项目表单实例 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectFormId;

    /**
     * 所属项目ID（序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    /**
     * 表单定义ID（序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long formId;

    /**
     * 表单名称（冗余展示，实时查询 form_definition）
     */
    private String formName;

    /**
     * 首个来源文件ID（Phase 4 回填，可空，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceFileId;

    /**
     * 抽取版本号
     */
    private Integer version;

    /**
     * 实例状态
     */
    private ProjectFormStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
