package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.service.ProjectFormService;
import com.aifp.aiagent.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 项目管理接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /project
 * <p>
 * 权限说明：本层不做权限判断，访问控制统一由 Service 层经
 * {@code ProjectAccessService} 前置校验。
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectFormService projectFormService;

    /**
     * 创建项目
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProjectCreateDTO dto) {
        return Result.success(projectService.createProject(dto));
    }

    /**
     * 查询项目详情
     */
    @GetMapping("/{id}")
    public Result<ProjectVO> get(@PathVariable Long id) {
        return Result.success(projectService.getProjectById(id));
    }

    /**
     * 分页查询项目列表（按创建时间倒序）
     */
    @GetMapping("/page")
    public Result<PageResult<ProjectVO>> page(@Valid PageQuery query) {
        return Result.success(projectService.page(query));
    }

    /**
     * 编辑项目（非空字段更新；项目编号不可修改）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody ProjectUpdateDTO dto) {
        projectService.updateProject(id, dto);
        return Result.success();
    }

    /**
     * 删除项目（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.deleteProject(id);
        return Result.success();
    }

    /**
     * 分页查询项目下的文件（按上传时间倒序）
     */
    @GetMapping("/{id}/files")
    public Result<PageResult<FileRecordVO>> files(@PathVariable Long id,
                                                  @Valid PageQuery query) {
        return Result.success(projectService.listProjectFiles(id, query));
    }

    /**
     * 将文件关联到项目（一个文件至多归属一个项目；重复关联本项目幂等）
     */
    @PostMapping("/{id}/file/{fileId}")
    public Result<Void> associateFile(@PathVariable Long id,
                                      @PathVariable Long fileId) {
        projectService.associateFile(id, fileId);
        return Result.success();
    }

    /**
     * 解除文件与项目的关联（文件须当前归属该项目）
     */
    @DeleteMapping("/{id}/file/{fileId}")
    public Result<Void> dissociateFile(@PathVariable Long id,
                                       @PathVariable Long fileId) {
        projectService.dissociateFile(id, fileId);
        return Result.success();
    }

    /**
     * 绑定表单到项目（同项目同表单唯一，重复绑定拒绝）
     */
    @PostMapping("/{id}/form")
    public Result<Long> bindForm(@PathVariable Long id,
                                 @Valid @RequestBody ProjectFormCreateDTO dto) {
        return Result.success(projectFormService.createProjectForm(id, dto));
    }

    /**
     * 分页查询项目下的表单实例（按创建时间倒序）
     */
    @GetMapping("/{id}/forms")
    public Result<PageResult<ProjectFormVO>> forms(@PathVariable Long id,
                                                   @Valid PageQuery query) {
        return Result.success(projectFormService.listProjectForms(id, query));
    }

    /**
     * 查询项目表单实例详情（实例不存在或归属其他项目时按不存在处理）
     */
    @GetMapping("/{id}/form/{projectFormId}")
    public Result<ProjectFormVO> form(@PathVariable Long id,
                                      @PathVariable Long projectFormId) {
        return Result.success(projectFormService.getProjectForm(id, projectFormId));
    }
}
