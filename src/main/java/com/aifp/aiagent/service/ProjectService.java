package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.*;

import java.util.List;

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

    /**
     * 分页查询项目下的文件（项目可访问且存在才允许查询）。
     *
     * @param projectId 项目ID
     * @param query     分页参数
     * @return 项目文件分页结果
     */
    PageResult<FileRecordVO> listProjectFiles(Long projectId, PageQuery query);

    /**
     * 将文件关联到项目（一个文件至多归属一个项目；重复关联本项目幂等）。
     *
     * @param projectId 项目ID
     * @param fileId    文件记录ID
     */
    void associateFile(Long projectId, Long fileId);

    /**
     * 解除文件与项目的关联（文件须当前归属该项目）。
     *
     * @param projectId 项目ID
     * @param fileId    文件记录ID
     */
    void dissociateFile(Long projectId, Long fileId);

    /**
     * 全量项目 ID 清单（非分页；Phase 10 跨项目比较的可访问项目集合来源）。
     * <p>
     * 需求 §三十五：跨项目查询必须基于"当前用户可访问项目集合"，禁止全库裸查。
     * 当前无用户体系（{@link ProjectAccessService} 默认放行），本方法即全部项目；
     * 接入用户体系后由权限过滤收窄。id 升序保证输出稳定。
     * 当前版本限制：非分页全量返回，与 PageQuery 上限策略无关（项目量级可控）。
     *
     * @return 项目 ID 列表（id 升序）
     */
    List<Long> listAllProjectIds();
}
