package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.FormCreateDTO;
import com.aifp.aiagent.dto.FormFieldCreateDTO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.service.FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 动态表单管理接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /form
 *
 * @author aiFileParser
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
}
