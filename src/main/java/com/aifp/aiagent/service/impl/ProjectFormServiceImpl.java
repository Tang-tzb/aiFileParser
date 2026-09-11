package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.dto.ProjectFormCreateDTO;
import com.aifp.aiagent.dto.ProjectFormVO;
import com.aifp.aiagent.entity.FormDefinition;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.entity.ProjectForm;
import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FormDefinitionMapper;
import com.aifp.aiagent.repository.ProjectFormMapper;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.FormService;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.ProjectFormService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 项目表单实例服务实现
 * <p>
 * 权限约束：所有操作前置调用 {@link ProjectAccessService#canAccess(Long)}，
 * 权限判断不进入 Controller 层；默认实现全放行，后续接入用户体系无需改动本类调用方式。
 * <p>
 * 编排约束：项目域守门（权限 + 项目存在性）在本类；表单定义存在性校验复用
 * {@link FormService#getFormById(Long)}（5001），不重复实现。
 * {@code formName} 为展示冗余，列表场景批量查询填充，详情场景单次查询填充。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFormServiceImpl implements ProjectFormService {

    private final ProjectMapper projectMapper;
    private final ProjectAccessService projectAccessService;
    private final ProjectFormMapper projectFormMapper;
    private final FormService formService;
    private final FormDefinitionMapper formDefinitionMapper;

    @Override
    public Long createProjectForm(Long projectId, ProjectFormCreateDTO dto) {
        ensureProjectGuard(projectId);
        // 表单定义存在性校验（不存在时 FormService 抛 5001），同时取名称用于日志
        formService.getFormById(dto.getFormId());
        ensureFormNotBound(projectId, dto.getFormId());
        ProjectForm projectForm = new ProjectForm();
        projectForm.setProjectId(projectId);
        projectForm.setFormId(dto.getFormId());
        // 首个来源文件ID由 Phase 4 抽取触发时回填，初始为 null
        projectForm.setVersion(1);
        projectForm.setStatus(ProjectFormStatus.ACTIVE);
        projectFormMapper.insert(projectForm);
        log.info("绑定表单到项目成功 projectFormId={}, projectId={}, formId={}",
                projectForm.getId(), projectId, dto.getFormId());
        return projectForm.getId();
    }

    @Override
    public PageResult<ProjectFormVO> listProjectForms(Long projectId, PageQuery query) {
        ensureProjectGuard(projectId);
        Page<ProjectForm> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<ProjectForm> wrapper = new LambdaQueryWrapper<ProjectForm>()
                .eq(ProjectForm::getProjectId, projectId)
                .orderByDesc(ProjectForm::getCreateTime);
        Page<ProjectForm> result = projectFormMapper.selectPage(page, wrapper);
        List<ProjectFormVO> records = result.getRecords().stream()
                .map(this::toVO)
                .toList();
        fillFormNames(records);
        return PageResult.of(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), records);
    }

    @Override
    public ProjectFormVO getProjectForm(Long projectId, Long projectFormId) {
        ensureProjectGuard(projectId);
        ProjectForm projectForm = projectFormMapper.selectById(projectFormId);
        // 不存在或归属其他项目统一按"不存在"处理，不泄露跨项目资源存在性
        if (projectForm == null || !Objects.equals(projectForm.getProjectId(), projectId)) {
            throw new BusinessException(ResultCode.PROJECT_FORM_NOT_FOUND);
        }
        ProjectFormVO vo = toVO(projectForm);
        fillFormNames(List.of(vo));
        return vo;
    }

    // ==================== 内部方法 ====================

    /**
     * 项目访问权限 + 项目存在性守门（权限判断唯一入口，Controller 不感知权限）
     */
    private void ensureProjectGuard(Long projectId) {
        if (!projectAccessService.canAccess(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
    }

    /**
     * 同项目同表单唯一性校验（uk_project_form 兜底，应用层先行给出业务语义错误）
     */
    private void ensureFormNotBound(Long projectId, Long formId) {
        Long count = projectFormMapper.selectCount(
                new LambdaQueryWrapper<ProjectForm>()
                        .eq(ProjectForm::getProjectId, projectId)
                        .eq(ProjectForm::getFormId, formId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PROJECT_FORM_DUPLICATE,
                    "表单已绑定该项目: formId=" + formId);
        }
    }

    /**
     * 批量填充表单名称（formId 去重后一次 selectBatchIds，避免 N+1）
     */
    private void fillFormNames(List<ProjectFormVO> records) {
        List<Long> formIds = records.stream()
                .map(ProjectFormVO::getFormId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (formIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = formDefinitionMapper.selectBatchIds(formIds).stream()
                .collect(Collectors.toMap(FormDefinition::getId, FormDefinition::getFormName));
        records.forEach(vo -> vo.setFormName(nameById.get(vo.getFormId())));
    }

    private ProjectFormVO toVO(ProjectForm projectForm) {
        ProjectFormVO vo = new ProjectFormVO();
        vo.setProjectFormId(projectForm.getId());
        vo.setProjectId(projectForm.getProjectId());
        vo.setFormId(projectForm.getFormId());
        vo.setSourceFileId(projectForm.getSourceFileId());
        vo.setVersion(projectForm.getVersion());
        vo.setStatus(projectForm.getStatus());
        vo.setCreateTime(projectForm.getCreateTime());
        vo.setUpdateTime(projectForm.getUpdateTime());
        return vo;
    }
}
