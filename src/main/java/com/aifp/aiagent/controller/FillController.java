package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.service.FieldExtractorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
     * <p>
     * 返回 {@link ExtractionResult} 含类型化值、剩余字段错误与 LLM 调用次数，
     * 前端可据此展示可靠性元数据（如哪些字段校验未通过、是否经过 Retry）。
     */
    @PostMapping("/{formId}")
    public Result<ExtractionResult> fill(
            @PathVariable Long formId,
            @RequestParam Long fileId) {
        return Result.success(fieldExtractorService.extract(formId, fileId));
    }
}
