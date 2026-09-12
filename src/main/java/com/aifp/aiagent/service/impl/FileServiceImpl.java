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
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.FileService;
import com.aifp.aiagent.service.ProjectAccessService;
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
    private final ProjectMapper projectMapper;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileUploadVO upload(MultipartFile file, Long projectId) {
        validateNotEmpty(file);
        FileType fileType = resolveType(file);

        String filePath = fileStorageService.store(file, fileType);

        FileRecord record = new FileRecord();
        record.setFileName(file.getOriginalFilename());
        record.setFileType(fileType);
        record.setFilePath(filePath);
        record.setStatus(FileStatus.UPLOADED);
        // 上传即归属项目：projectId 非空时校验可访问且存在；null 保持历史行为
        if (projectId != null) {
            ensureProjectUsable(projectId);
            record.setProjectId(projectId);
        }
        fileRecordMapper.insert(record);

        log.info("文件上传成功 fileId={}, name={}, type={}, projectId={}",
                record.getId(), record.getFileName(), fileType, record.getProjectId());
        return toUploadVO(record);
    }

    /**
     * 状态流转（阶段 13 状态机唯一写入口）：
     * 同态写视为幂等 no-op；非法迁移（{@link FileStatus#canTransitionTo} 白名单外，
     * 含终态 SUCCESS 任何出边）抛 {@code FILE_STATUS_ILLEGAL_TRANSITION} 且不落库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, FileStatus status) {
        FileRecord record = fileRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        FileStatus current = record.getStatus();
        // 同态写幂等 no-op（如 markFailed 双写 FAILED→FAILED）
        if (current == status) {
            log.info("文件状态同态跳过 fileId={}, status={}", id, status);
            return;
        }
        if (!current.canTransitionTo(status)) {
            log.warn("非法文件状态流转已拒绝 fileId={}, {} → {}", id, current, status);
            throw new BusinessException(ResultCode.FILE_STATUS_ILLEGAL_TRANSITION,
                    "非法状态流转: " + current + " → " + status);
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

    @Override
    public PageResult<FileRecordVO> pageByProject(Long projectId, PageQuery query) {
        // 项目文件分页：project_id 精确过滤，按创建时间倒序
        Page<FileRecord> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<FileRecord> wrapper = new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getProjectId, projectId)
                .orderByDesc(FileRecord::getCreateTime);
        Page<FileRecord> result = fileRecordMapper.selectPage(page, wrapper);
        List<FileRecordVO> records = result.getRecords().stream()
                .map(this::toRecordVO)
                .toList();
        return PageResult.of(result.getTotal(), result.getPages(),
                result.getCurrent(), result.getSize(), records);
    }

    @Override
    public List<Long> listFileIdsByProject(Long projectId) {
        // 非分页主键查询：仅供项目范围检索兜底过滤（Phase 6），实时读取当前绑定关系
        return fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                        .select(FileRecord::getId)
                        .eq(FileRecord::getProjectId, projectId)
                        .orderByAsc(FileRecord::getId))
                .stream()
                .map(FileRecord::getId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void associateToProject(Long projectId, Long fileId) {
        FileRecord record = requireFile(fileId);
        Long current = record.getProjectId();
        // 已归属本项目：幂等 no-op；已归属其他项目：拒绝
        if (projectId.equals(current)) {
            log.info("文件已归属项目，幂等跳过 fileId={}, projectId={}", fileId, projectId);
            return;
        }
        if (current != null) {
            throw new BusinessException(ResultCode.PROJECT_FILE_ALREADY_BOUND,
                    "文件已关联其他项目: " + current);
        }
        record.setProjectId(projectId);
        fileRecordMapper.updateById(record);
        log.info("文件关联项目成功 fileId={}, projectId={}", fileId, projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dissociateFromProject(Long projectId, Long fileId) {
        FileRecord record = requireFile(fileId);
        // 未归属该项目（null 或其他项目）：拒绝；匹配则置空
        if (!projectId.equals(record.getProjectId())) {
            throw new BusinessException(ResultCode.PROJECT_FILE_NOT_IN_PROJECT,
                    "文件未关联该项目 fileId=" + fileId + ", projectId=" + projectId);
        }
        record.setProjectId(null);
        fileRecordMapper.updateById(record);
        log.info("文件解除项目关联成功 fileId={}, projectId={}", fileId, projectId);
    }

    // ==================== 内部方法 ====================

    /**
     * 上传/关联场景的项目可用性校验：权限 + 存在性
     */
    private void ensureProjectUsable(Long projectId) {
        if (!projectAccessService.canAccess(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
    }

    /**
     * 加载文件记录，不存在抛 FILE_NOT_FOUND(2004)
     */
    private FileRecord requireFile(Long fileId) {
        FileRecord record = fileRecordMapper.selectById(fileId);
        if (record == null) {
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        return record;
    }

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
        vo.setProjectId(r.getProjectId());
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
        vo.setProjectId(r.getProjectId());
        vo.setStatus(r.getStatus());
        vo.setCreateTime(r.getCreateTime());
        vo.setUpdateTime(r.getUpdateTime());
        return vo;
    }
}
