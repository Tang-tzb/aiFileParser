package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.*;
import com.aifp.aiagent.service.FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 动态表单管理接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /form
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/form")
@RequiredArgsConstructor
public class FormController {

    private final FormService formService;

    /**
     * 创建表单（表单头 + 可选字段列表）
     */
    @PostMapping("/create")
    public Result<Long> create(@Valid @RequestBody FormCreateDTO dto) {
        return Result.success(formService.createForm(dto));
    }

    /**
     * 查询表单详情（含字段列表）
     */
    @GetMapping("/{id}")
    public Result<FormVO> get(@PathVariable Long id) {
        return Result.success(formService.getFormById(id));
    }

    /**
     * 分页查询表单列表（按创建时间倒序，列表不含字段详情）
     */
    @GetMapping("/page")
    public Result<PageResult<FormVO>> page(@Valid PageQuery query) {
        return Result.success(formService.page(query));
    }

    /**
     * 向表单追加单个字段
     */
    @PostMapping("/{id}/field")
    public Result<Long> addField(@PathVariable Long id,
                                 @Valid @RequestBody FormFieldCreateDTO dto) {
        return Result.success(formService.addField(id, dto));
    }

    /**
     * 删除表单下指定字段（软删除）
     */
    @DeleteMapping("/{id}/field/{fieldId}")
    public Result<Void> deleteField(@PathVariable Long id,
                                    @PathVariable Long fieldId) {
        formService.deleteField(id, fieldId);
        return Result.success();
    }

    /**
     * 删除表单（软删除表单 + 级联软删除其下所有字段）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        formService.deleteForm(id);
        return Result.success();
    }
}
