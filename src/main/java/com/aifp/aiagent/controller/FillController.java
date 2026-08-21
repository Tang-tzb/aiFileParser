package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.service.FieldExtractorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 自动填表接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /fill
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/fill")
@RequiredArgsConstructor
public class FillController {

    private final FieldExtractorService fieldExtractorService;

    /**
     * 根据 formId 的字段定义，从 fileId 对应文档抽取字段值并返回。
     */
    @PostMapping("/{formId}")
    public Result<Map<String, Object>> fill(
            @PathVariable Long formId,
            @RequestParam Long fileId) {
        return Result.success(fieldExtractorService.extract(formId, fileId));
    }
}
