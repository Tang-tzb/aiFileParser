package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.*;

/**
 * 项目管理服务
 * <p>
 * Project 为业务聚合根：项目级数据（文件、表单实例、结构化字段值）的归属对象。
 * 所有读写均经 {@link ProjectAccessService} 权限校验（当前无用户体系，默认放行）。
 *
 * @author Tang_tzb
 */
public interface ProjectService {

    /**
     * 创建项目（项目编号全库查重，uk_project_no 兜底）。
     *
     * @param dto 项目创建数据
     * @return 新建项目ID
     */
    Long createProject(ProjectCreateDTO dto);

    /**
     * 查询项目详情。
     *
     * @param id 项目ID
     * @return 项目详情 VO
     */
    ProjectVO getProjectById(Long id);

    /**
     * 分页查询项目列表（按 createTime DESC）。
     *
     * @param query 分页参数
     * @return 分页结果
     */
    PageResult<ProjectVO> page(PageQuery query);

    /**
     * 编辑项目（非空字段选择性更新；项目编号不可修改）。
     *
     * @param id  项目ID
     * @param dto 编辑数据
     */
    void updateProject(Long id, ProjectUpdateDTO dto);

    /**
     * 删除项目（逻辑删除）。
     * <p>
     * Phase 1 无子资源，仅删本体；后续阶段引入项目表单/文件关联时补充级联约束。
     *
     * @param id 项目ID
     */
    void deleteProject(Long id);
}
