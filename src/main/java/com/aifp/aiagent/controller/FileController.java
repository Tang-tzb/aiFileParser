package com.aifp.aiagent.controller;

import com.aifp.aiagent.common.Result;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FileUploadVO;
import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.service.FileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件管理接口
 * <p>
 * 完整路径前缀：/aifp（context-path）+ /file
 *
 * @author Tang_tzb
 */
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传文件（PDF/Excel/Word），返回文件记录与 UPLOADED 状态。
     * <p>
     * {@code projectId} 可选：传入时上传即归属该项目（校验项目可访问且存在）；
     * 不传保持历史行为（未关联项目，历史数据兼容）。
     */
    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "projectId", required = false) Long projectId) {
        return Result.success(fileService.upload(file, projectId));
    }

    /**
     * 分页查询文件列表（按上传时间倒序）
     */
    @GetMapping("/page")
    public Result<PageResult<FileRecordVO>> page(@Valid PageQuery query) {
        return Result.success(fileService.page(query));
    }
}
