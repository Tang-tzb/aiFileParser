package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.dto.ProjectFormCreateDTO;
import com.aifp.aiagent.dto.ProjectFormVO;

/**
 * 项目表单实例服务
 * <p>
 * Project 聚合子域：将表单定义（form_definition）绑定到项目形成实例（project_form）。
 * 结构化字段值（project_form_field_value）的写入由 Phase 4 抽取链路负责，本服务不涉及。
 * <p>
 * 权限约束：所有操作前置调用 {@link ProjectAccessService#canAccess(Long)} 校验，
 * 权限判断不进入 Controller 层。
 *
 * @author Tang_tzb
 */
public interface ProjectFormService {

    /**
     * 绑定表单到项目（创建项目表单实例）。
     * <p>
     * 同项目同表单唯一：重复绑定抛 PROJECT_FORM_DUPLICATE（uk_project_form 兜底）。
     *
     * @param projectId 项目ID
     * @param dto       绑定数据（表单定义ID）
     * @return 新建项目表单实例ID
     */
    Long createProjectForm(Long projectId, ProjectFormCreateDTO dto);

    /**
     * 分页查询项目下的表单实例（按 createTime DESC）。
     * <p>
     * 每条记录实时填充表单名称（formId 批量查询，避免 N+1）。
     *
     * @param projectId 项目ID
     * @param query     分页参数
     * @return 项目表单实例分页结果
     */
    PageResult<ProjectFormVO> listProjectForms(Long projectId, PageQuery query);

    /**
     * 查询项目表单实例详情。
     * <p>
     * 实例不存在或归属其他项目时统一抛 PROJECT_FORM_NOT_FOUND（不泄露跨项目资源存在性）。
     *
     * @param projectId     项目ID
     * @param projectFormId 项目表单实例ID
     * @return 项目表单实例详情 VO
     */
    ProjectFormVO getProjectForm(Long projectId, Long projectFormId);
}
