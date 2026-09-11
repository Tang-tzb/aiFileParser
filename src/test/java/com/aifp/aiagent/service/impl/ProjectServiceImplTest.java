package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.entity.enums.ProjectStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.ProjectMapper;
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
import static org.mockito.Mockito.*;

/**
 * {@link ProjectServiceImpl} 单元测试
 * <p>
 * 覆盖：项目编号查重（6002）、资源不存在（6001）、权限前置调用链、
 * 非空字段选择性更新、逻辑删除与分页转换。
 * Mapper 与 {@link ProjectAccessService} 均为 Mockito 模拟，完全离线。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long FILE_ID = 1785800001L;
    private static final String PROJECT_NO = "PRJ-001";

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private com.aifp.aiagent.service.FileService fileService;

    private ProjectServiceImpl projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl(projectMapper, projectAccessService, fileService);
    }

    // ==================== createProject ====================

    /**
     * 创建成功：insert 被调用，字段完整映射，返回雪花主键
     */
    @Test
    void createProject_success_insertsAndReturnsId() {
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(projectMapper.insert(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return 1;
        });

        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setProjectNo(PROJECT_NO);
        dto.setProjectName("职业教育园一期");
        dto.setDescription("desc");

        Long id = projectService.createProject(dto);

        assertThat(id).isEqualTo(PROJECT_ID);
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(captor.capture());
        Project saved = captor.getValue();
        assertThat(saved.getProjectNo()).isEqualTo(PROJECT_NO);
        assertThat(saved.getProjectName()).isEqualTo("职业教育园一期");
        assertThat(saved.getDescription()).isEqualTo("desc");
    }

    /**
     * 编号已存在：抛 PROJECT_NO_DUPLICATE(6002)，不 insert
     */
    @Test
    void createProject_duplicateProjectNo_rejectedWithoutInsert() {
        when(projectMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ProjectCreateDTO dto = new ProjectCreateDTO();
        dto.setProjectNo(PROJECT_NO);
        dto.setProjectName("职业教育园一期");

        assertThatThrownBy(() -> projectService.createProject(dto))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NO_DUPLICATE.getCode()));

        verify(projectMapper, never()).insert(any(Project.class));
    }

    // ==================== getProjectById ====================

    /**
     * 详情成功：权限校验先于查询，实体转 VO 字段一致
     */
    @Test
    void getProjectById_success_mapsVo() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());

        ProjectVO vo = projectService.getProjectById(PROJECT_ID);

        assertThat(vo.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(vo.getProjectNo()).isEqualTo(PROJECT_NO);
        assertThat(vo.getProjectName()).isEqualTo("职业教育园一期");
        assertThat(vo.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    /**
     * 项目不存在：抛 PROJECT_NOT_FOUND(6001)
     */
    @Test
    void getProjectById_notFound_throws() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectService.getProjectById(PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));
    }

    /**
     * 权限拒绝：抛 FORBIDDEN(403)，不触发查询
     */
    @Test
    void getProjectById_accessDenied_throwsForbiddenWithoutQuery() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> projectService.getProjectById(PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verify(projectMapper, never()).selectById(any());
    }

    // ==================== updateProject ====================

    /**
     * 非空字段选择性更新：null 字段保持原值不覆盖
     */
    @Test
    void updateProject_partialFields_onlyOverwritesProvided() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());
        when(projectMapper.updateById(any(Project.class))).thenReturn(1);

        ProjectUpdateDTO dto = new ProjectUpdateDTO();
        dto.setProjectName("新名称");
        // description / status 不传，应保持原值

        projectService.updateProject(PROJECT_ID, dto);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        Project updated = captor.getValue();
        assertThat(updated.getProjectName()).isEqualTo("新名称");
        assertThat(updated.getDescription()).isEqualTo("desc");
        assertThat(updated.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    /**
     * 编辑不存在项目：抛 PROJECT_NOT_FOUND(6001)，不 update
     */
    @Test
    void updateProject_notFound_throwsWithoutUpdate() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectService.updateProject(PROJECT_ID, new ProjectUpdateDTO()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verify(projectMapper, never()).updateById(any(Project.class));
    }

    // ==================== deleteProject ====================

    /**
     * 删除成功：权限校验 + 存在校验后 deleteById
     */
    @Test
    void deleteProject_success_deletesById() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());

        projectService.deleteProject(PROJECT_ID);

        verify(projectMapper).deleteById(PROJECT_ID);
    }

    /**
     * 删除不存在项目：抛 PROJECT_NOT_FOUND(6001)，不 delete
     */
    @Test
    void deleteProject_notFound_throwsWithoutDelete() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectService.deleteProject(PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verify(projectMapper, never()).deleteById(any());
    }

    // ==================== page ====================

    /**
     * 分页转换：IPage 记录转 PageResult，字段透传
     */
    @Test
    void page_success_convertsPageResult() {
        Page<Project> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(sampleProject(), sampleProject()));

        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PageQuery query = new PageQuery();
        PageResult<ProjectVO> result = projectService.page(query);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getProjectNo()).isEqualTo(PROJECT_NO);
    }

    // ==================== 项目文件关联编排（Phase 2） ====================

    /**
     * 项目文件分页：权限+存在校验通过后委托 FileService.pageByProject
     */
    @Test
    void listProjectFiles_guardsProject_thenDelegates() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());

        PageQuery query = new PageQuery();
        projectService.listProjectFiles(PROJECT_ID, query);

        verify(fileService).pageByProject(PROJECT_ID, query);
    }

    /**
     * 项目文件分页：项目不存在 → 6001，不触碰 FileService
     */
    @Test
    void listProjectFiles_projectNotFound_throwsWithoutDelegation() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> projectService.listProjectFiles(PROJECT_ID, new PageQuery()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));

        verifyNoInteractions(fileService);
    }

    /**
     * 关联文件：权限拒绝 → FORBIDDEN，不触碰 FileService
     */
    @Test
    void associateFile_accessDenied_throwsWithoutDelegation() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> projectService.associateFile(PROJECT_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verifyNoInteractions(fileService);
    }

    /**
     * 关联文件：项目守门通过后委托 FileService.associateToProject
     */
    @Test
    void associateFile_guardsProject_thenDelegates() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());

        projectService.associateFile(PROJECT_ID, FILE_ID);

        verify(fileService).associateToProject(PROJECT_ID, FILE_ID);
    }

    /**
     * 解除关联：项目守门通过后委托 FileService.dissociateFromProject
     */
    @Test
    void dissociateFile_guardsProject_thenDelegates() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sampleProject());

        projectService.dissociateFile(PROJECT_ID, FILE_ID);

        verify(fileService).dissociateFromProject(PROJECT_ID, FILE_ID);
    }

    // ==================== 测试辅助 ====================

    private Project sampleProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectNo(PROJECT_NO);
        project.setProjectName("职业教育园一期");
        project.setDescription("desc");
        project.setStatus(ProjectStatus.ACTIVE);
        return project;
    }
}
