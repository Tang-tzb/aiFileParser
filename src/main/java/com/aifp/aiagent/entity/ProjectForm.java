package com.aifp.aiagent.entity;

import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目表单实例实体
 * <p>
 * 对应表 project_form：表单定义（form_definition）在项目维度的绑定实例。
 * 同一 {@code (projectId, formId)} 唯一（uk_project_form）；
 * {@code sourceFileId} 与 {@code version} 由 Phase 4 抽取链路（ProjectFormPersistenceService）维护。
 * 字段值明细见 {@link ProjectFormFieldValue}（字段定义与字段值解耦）。
 *
 * @author Tang_tzb
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_form")
public class ProjectForm extends BaseEntity {

    /**
     * 所属项目ID
     */
    private Long projectId;

    /**
     * 表单定义ID（form_definition）
     */
    private Long formId;

    /**
     * 首个来源文件ID（Phase 4 抽取触发时回填，可空）
     */
    private Long sourceFileId;

    /**
     * 抽取版本号（Phase 4 起递增，本阶段恒为 1）
     */
    private Integer version;

    /**
     * 实例状态
     */
    private ProjectFormStatus status;
}
