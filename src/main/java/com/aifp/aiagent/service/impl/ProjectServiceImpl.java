package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目管理服务实现
 * <p>
 * 权限约束：所有读写操作前置调用 {@link ProjectAccessService#canAccess(Long)}，
 * 权限判断不进入 Controller 层；默认实现全放行，后续接入用户体系无需改动本类调用方式。
 * <p>
 * 编排约束：项目域守门（权限 + 存在性）在本类，file_record 读写收敛在
 * {@link FileService}（与 ParseTaskServiceImpl 编排 FormService/FileService 先例一致）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectAccessService projectAccessService;
    private final FileService fileService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProject(ProjectCreateDTO dto) {
        ensureProjectNoNotDuplicate(dto.getProjectNo());
        Project project = new Project();
        project.setProjectNo(dto.getProjectNo());
        project.setProjectName(dto.getProjectName());
        project.setDescription(dto.getDescription());
        // 状态默认 ACTIVE，未显式赋值时由 DB DEFAULT 兜底
        projectMapper.insert(project);
        log.info("创建项目成功 projectId={}, projectNo={}", project.getId(), project.getProjectNo());
        return project.getId();
    }

    @Override
    public ProjectVO getProjectById(Long id) {
        ensureAccess(id);
        Project project = requireProject(id);
        return toVO(project);
    }

    @Override
    public PageResult<ProjectVO> page(PageQuery query) {
        // 分页查询项目元数据，按创建时间倒序
        Page<Project> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .orderByDesc(Project::getCreateTime);
        Page<Project> result = projectMapper.selectPage(page, wrapper);
        List<ProjectVO> records = result.getRecords().stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProject(Long id, ProjectUpdateDTO dto) {
        ensureAccess(id);
        Project project = requireProject(id);
        // 非空字段选择性更新：null = 不修改；projectNo 不在可编辑范围
        if (dto.getProjectName() != null) {
            project.setProjectName(dto.getProjectName());
        }
        if (dto.getDescription() != null) {
            project.setDescription(dto.getDescription());
        }
        if (dto.getStatus() != null) {
            project.setStatus(dto.getStatus());
        }
        projectMapper.updateById(project);
        log.info("更新项目成功 projectId={}, projectNo={}", id, project.getProjectNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long id) {
        ensureAccess(id);
        requireProject(id);
        // @TableLogic 自动生效为逻辑删除
        projectMapper.deleteById(id);
        log.info("删除项目成功 projectId={}", id);
    }

    @Override
    public PageResult<FileRecordVO> listProjectFiles(Long projectId, PageQuery query) {
        // 项目域守门：可访问 + 存在才允许查看项目文件
        ensureAccess(projectId);
        requireProject(projectId);
        return fileService.pageByProject(projectId, query);
    }

    @Override
    public void associateFile(Long projectId, Long fileId) {
        ensureAccess(projectId);
        requireProject(projectId);
        fileService.associateToProject(projectId, fileId);
    }

    @Override
    public void dissociateFile(Long projectId, Long fileId) {
        ensureAccess(projectId);
        requireProject(projectId);
        fileService.dissociateFromProject(projectId, fileId);
    }

    @Override
    public List<Long> listAllProjectIds() {
        // 需求 §三十五：跨项目比较的可访问项目集合来源（当前无用户体系=全部项目）；
        // id 升序保证输出稳定；@TableLogic 自动过滤已删项目
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .orderByAsc(Project::getId))
                .stream()
                .map(Project::getId)
                .toList();
    }

    // ==================== 内部方法 ====================

    /**
     * 项目访问权限前置校验（权限判断唯一入口，Controller 不感知权限）
     */
    private void ensureAccess(Long projectId) {
        if (!projectAccessService.canAccess(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 加载项目，不存在抛 PROJECT_NOT_FOUND
     */
    private Project requireProject(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private void ensureProjectNoNotDuplicate(String projectNo) {
        Long count = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getProjectNo, projectNo));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PROJECT_NO_DUPLICATE,
                    "项目编号已存在: " + projectNo);
        }
    }

    private ProjectVO toVO(Project project) {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(project.getId());
        vo.setProjectNo(project.getProjectNo());
        vo.setProjectName(project.getProjectName());
        vo.setDescription(project.getDescription());
        vo.setStatus(project.getStatus());
        vo.setCreateTime(project.getCreateTime());
        vo.setUpdateTime(project.getUpdateTime());
        return vo;
    }
}
