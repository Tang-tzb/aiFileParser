package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.*;
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
}
