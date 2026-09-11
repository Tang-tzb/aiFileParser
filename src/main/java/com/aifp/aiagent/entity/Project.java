package com.aifp.aiagent.entity;

import com.aifp.aiagent.entity.enums.ProjectStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目实体
 * <p>
 * 对应表 project：业务聚合根，项目级数据（文件、表单实例、结构化字段值）的归属对象。
 * 项目编号 {@code projectNo} 为业务唯一标识，创建后不可修改；
 * 文件（Phase 2）、表单实例（Phase 3）等子资源在后续阶段挂接到项目。
 *
 * @author Tang_tzb
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    /**
     * 项目编号（业务唯一标识，uk_project_no 约束）
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
}
