package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectFormServiceImpl} 单元测试
 * <p>
 * 覆盖：绑定表单正向（version=1/ACTIVE 初始值）、项目守门（403/6001）、
 * 表单定义存在性复用（5001）、同项目同表单唯一（6005）、
 * 分页转换 + formName 批量填充、详情跨项目隔离（6006 不泄露存在性）。
 * Mapper 与 {@link ProjectAccessService}/{@link FormService} 均为 Mockito 模拟，完全离线。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectFormServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long FORM_ID = 1785700001L;
    private static final Long PROJECT_FORM_ID = 1785600001L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private ProjectFormMapper projectFormMapper;
    @Mock
    private FormService formService;
    @Mock
    private FormDefinitionMapper formDefinitionMapper;

    private ProjectFormServiceImpl projectFormService;

    @BeforeEach
    void setUp() {
        projectFormService = new ProjectFormServiceImpl(
                projectMapper, projectAccessService, projectFormMapper, formService, formDefinitionMapper);
    }

    // ==================== createProjectForm ====================

    /**
     * 绑定成功：insert 被调用，初始 version=1、status=ACTIVE、sourceFileId=null
     */
    @Test
    void createProjectForm_success_insertsWithDefaults() {
        guardProjectPasses();
        when(formService.getFormById(FORM_ID)).thenReturn(sampleFormVO());
        when(projectFormMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projectFormMapper.insert(any(ProjectForm.class))).thenAnswer(inv -> {
            ProjectForm pf = inv.getArgument(0);
            pf.setId(PROJECT_FORM_ID);
            return 1;
        });

        Long id = projectFormService.createProjectForm(PROJECT_ID, createDTO());

        assertThat(id).isEqualTo(PROJECT_FORM_ID);
        ArgumentCaptor<ProjectForm> captor = ArgumentCaptor.forClass(ProjectForm.class);
        verify(projectFormMapper).insert(captor.capture());
        ProjectForm saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getFormId()).isEqualTo(FORM_ID);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(ProjectFormStatus.ACTIVE);
        assertThat(saved.getSourceFileId()).isNull();
    }

    /**
     * 项目不存在：抛 PROJECT_NOT_FOUND(6001)，不触发表单校验与 insert
     */
    @Test
    void createProjectForm_projectNotFound_throwsWithoutInsert() {
        guardProjectPasses();
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectFormService.createProjectForm(PROJECT_ID, createDTO()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verifyNoInteractions(formService, projectFormMapper);
    }

    /**
     * 权限拒绝：抛 FORBIDDEN(403)，不触发项目查询
     */
    @Test
    void createProjectForm_accessDenied_throwsForbidden() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> projectFormService.createProjectForm(PROJECT_ID, createDTO()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verify(projectMapper, never()).selectById(any());
        verifyNoInteractions(formService, projectFormMapper);
    }

    /**
     * 表单定义不存在：FormService 抛 FORM_NOT_FOUND(5001)，不 insert
     */
    @Test
    void createProjectForm_formNotFound_propagates5001() {
        guardProjectPasses();
        when(formService.getFormById(FORM_ID))
                .thenThrow(new BusinessException(ResultCode.FORM_NOT_FOUND));

        assertThatThrownBy(() -> projectFormService.createProjectForm(PROJECT_ID, createDTO()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORM_NOT_FOUND.getCode()));

        verify(projectFormMapper, never()).insert(any(ProjectForm.class));
    }

    /**
     * 重复绑定：抛 PROJECT_FORM_DUPLICATE(6005)，不 insert（uk_project_form 兜底）
     */
    @Test
    void createProjectForm_duplicateBinding_throws6005WithoutInsert() {
        guardProjectPasses();
        when(formService.getFormById(FORM_ID)).thenReturn(sampleFormVO());
        when(projectFormMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> projectFormService.createProjectForm(PROJECT_ID, createDTO()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FORM_DUPLICATE.getCode()));

        verify(projectFormMapper, never()).insert(any(ProjectForm.class));
    }

    // ==================== listProjectForms ====================

    /**
     * 分页成功：IPage 记录转 PageResult，formName 经 selectBatchIds 一次批量填充
     */
    @Test
    void listProjectForms_success_fillsFormNamesInBatch() {
        guardProjectPasses();
        Page<ProjectForm> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(sampleProjectForm(PROJECT_FORM_ID), sampleProjectForm(1785600002L)));
        when(projectFormMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(formDefinitionMapper.selectBatchIds(anyList()))
                .thenReturn(List.of(formDefinition(FORM_ID, "施工许可证表单")));

        PageResult<ProjectFormVO> result = projectFormService.listProjectForms(PROJECT_ID, new PageQuery());

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getProjectFormId()).isEqualTo(PROJECT_FORM_ID);
        assertThat(result.getRecords().get(0).getFormName()).isEqualTo("施工许可证表单");
        // 两个实例 formId 相同 → 去重后仅批量查询一次
        verify(formDefinitionMapper, times(1)).selectBatchIds(anyList());
    }

    /**
     * 项目不存在：抛 PROJECT_NOT_FOUND(6001)，不触碰 project_form
     */
    @Test
    void listProjectForms_projectNotFound_throwsWithoutQuery() {
        guardProjectPasses();
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectFormService.listProjectForms(PROJECT_ID, new PageQuery()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verifyNoInteractions(projectFormMapper, formDefinitionMapper);
    }

    // ==================== getProjectForm ====================

    /**
     * 详情成功：实体转 VO + formName 填充
     */
    @Test
    void getProjectForm_success_returnsVoWithFormName() {
        guardProjectPasses();
        when(projectFormMapper.selectById(PROJECT_FORM_ID)).thenReturn(sampleProjectForm(PROJECT_FORM_ID));
        when(formDefinitionMapper.selectBatchIds(anyList()))
                .thenReturn(List.of(formDefinition(FORM_ID, "施工许可证表单")));

        ProjectFormVO vo = projectFormService.getProjectForm(PROJECT_ID, PROJECT_FORM_ID);

        assertThat(vo.getProjectFormId()).isEqualTo(PROJECT_FORM_ID);
        assertThat(vo.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(vo.getFormId()).isEqualTo(FORM_ID);
        assertThat(vo.getFormName()).isEqualTo("施工许可证表单");
        assertThat(vo.getStatus()).isEqualTo(ProjectFormStatus.ACTIVE);
    }

    /**
     * 实例不存在：抛 PROJECT_FORM_NOT_FOUND(6006)
     */
    @Test
    void getProjectForm_notFound_throws6006() {
        guardProjectPasses();
        when(projectFormMapper.selectById(PROJECT_FORM_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectFormService.getProjectForm(PROJECT_ID, PROJECT_FORM_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FORM_NOT_FOUND.getCode()));
    }

    /**
     * 实例归属其他项目：统一按 6006 处理（不泄露跨项目资源存在性）
     */
    @Test
    void getProjectForm_ownedByOtherProject_throws6006() {
        guardProjectPasses();
        ProjectForm other = sampleProjectForm(PROJECT_FORM_ID);
        other.setProjectId(9999L);
        when(projectFormMapper.selectById(PROJECT_FORM_ID)).thenReturn(other);

        assertThatThrownBy(() -> projectFormService.getProjectForm(PROJECT_ID, PROJECT_FORM_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_FORM_NOT_FOUND.getCode()));
    }

    /**
     * 权限拒绝：抛 FORBIDDEN(403)，不触发实例查询
     */
    @Test
    void getProjectForm_accessDenied_throwsForbidden() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> projectFormService.getProjectForm(PROJECT_ID, PROJECT_FORM_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verify(projectFormMapper, never()).selectById(any());
    }

    // ==================== 测试辅助 ====================

    /**
     * 项目守门通过：canAccess=true + 项目存在
     */
    private void guardProjectPasses() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(new Project());
    }

    private ProjectFormCreateDTO createDTO() {
        ProjectFormCreateDTO dto = new ProjectFormCreateDTO();
        dto.setFormId(FORM_ID);
        return dto;
    }

    private FormVO sampleFormVO() {
        FormVO vo = new FormVO();
        vo.setFormId(FORM_ID);
        vo.setFormName("施工许可证表单");
        return vo;
    }

    private ProjectForm sampleProjectForm(Long id) {
        ProjectForm pf = new ProjectForm();
        pf.setId(id);
        pf.setProjectId(PROJECT_ID);
        pf.setFormId(FORM_ID);
        pf.setVersion(1);
        pf.setStatus(ProjectFormStatus.ACTIVE);
        return pf;
    }

    private FormDefinition formDefinition(Long id, String formName) {
        FormDefinition def = new FormDefinition();
        def.setId(id);
        def.setFormName(formName);
        return def;
    }
}
