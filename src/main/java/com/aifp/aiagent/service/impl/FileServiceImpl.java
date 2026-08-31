package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FileUploadVO;
import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.entity.FileRecord;
import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FileRecordMapper;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.storage.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件管理服务实现
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileRecordMapper fileRecordMapper;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileUploadVO upload(MultipartFile file) {
        validateNotEmpty(file);
        FileType fileType = resolveType(file);

        String filePath = fileStorageService.store(file, fileType);

        FileRecord record = new FileRecord();
        record.setFileName(file.getOriginalFilename());
        record.setFileType(fileType);
        record.setFilePath(filePath);
        record.setStatus(FileStatus.UPLOADED);
        fileRecordMapper.insert(record);

        log.info("文件上传成功 fileId={}, name={}, type={}", record.getId(), record.getFileName(), fileType);
        return toUploadVO(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, FileStatus status) {
        FileRecord record = fileRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        record.setStatus(status);
        fileRecordMapper.updateById(record);
        log.info("文件状态流转 fileId={}, status={}", id, status);
    }

    @Override
    public FileRecordVO getById(Long id) {
        FileRecord record = fileRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        return toRecordVO(record);
    }

    @Override
    public PageResult<FileRecordVO> page(PageQuery query) {
        // MyBatis-Plus 分页：Page 自动应用 PaginationInnerInterceptor
        Page<FileRecord> page = new Page<>(query.getPageNum(), query.getPageSize());
        // 按创建时间倒序：最近上传优先
        LambdaQueryWrapper<FileRecord> wrapper = new LambdaQueryWrapper<FileRecord>()
                .orderByDesc(FileRecord::getCreateTime);
        Page<FileRecord> result = fileRecordMapper.selectPage(page, wrapper);
        // 实体 → VO，复用 toRecordVO 保证字段映射零重复
        List<FileRecordVO> records = result.getRecords().stream()
                .map(this::toRecordVO)
                .toList();
        return PageResult.of(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), records);
    }

    // ==================== 内部方法 ====================

    private void validateNotEmpty(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "上传文件为空");
        }
    }

    private FileType resolveType(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = extractExtension(original);
        FileType type = FileType.ofExtension(ext);
        if (type == null || type == FileType.OTHER) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORT,
                    "不支持的文件类型: " + ext);
        }
        return type;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private FileUploadVO toUploadVO(FileRecord r) {
        FileUploadVO vo = new FileUploadVO();
        vo.setFileId(r.getId());
        vo.setFileName(r.getFileName());
        vo.setFileType(r.getFileType());
        vo.setFilePath(r.getFilePath());
        vo.setStatus(r.getStatus());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }

    private FileRecordVO toRecordVO(FileRecord r) {
        FileRecordVO vo = new FileRecordVO();
        vo.setFileId(r.getId());
        vo.setFileName(r.getFileName());
        vo.setFileType(r.getFileType());
        vo.setFilePath(r.getFilePath());
        vo.setStatus(r.getStatus());
        vo.setCreateTime(r.getCreateTime());
        vo.setUpdateTime(r.getUpdateTime());
        return vo;
    }
}
