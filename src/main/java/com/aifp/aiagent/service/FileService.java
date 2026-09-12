package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FileUploadVO;
import com.aifp.aiagent.dto.PageQuery;
import com.aifp.aiagent.dto.PageResult;
import com.aifp.aiagent.entity.enums.FileStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件管理服务
 * <p>
 * 负责文件上传、类型判断、记录保存与状态管理。
 * 不与 AI 解析耦合；状态流转方法供阶段 4/5/6 调用。
 *
 * @author Tang_tzb
 */
public interface FileService {

    /**
     * 上传文件：保存到存储 + 写入 file_record（状态 UPLOADED）。
     * <p>
     * {@code projectId} 非空时校验项目可访问且存在，上传即归属项目；
     * 为 null 时保持历史行为（未关联项目）。
     *
     * @param file      上传文件
     * @param projectId 所属项目ID（可选）
     * @return 上传结果 VO
     */
    FileUploadVO upload(MultipartFile file, Long projectId);

    /**
     * 更新文件处理状态（供解析流水线流转）。
     *
     * @param id     文件记录ID
     * @param status 目标状态
     */
    void updateStatus(Long id, FileStatus status);

    /**
     * 查询文件记录。
     *
     * @param id 文件记录ID
     * @return 文件记录 VO
     */
    FileRecordVO getById(Long id);

    /**
     * 分页查询文件记录（按 createTime DESC）。
     *
     * @param query 分页参数
     * @return 分页结果（每条为 FileRecordVO，含全部字段）
     */
    PageResult<FileRecordVO> page(PageQuery query);

    /**
     * 分页查询指定项目下的文件（按 createTime DESC）。
     *
     * @param projectId 项目ID（存在性/权限由调用方项目域校验）
     * @param query     分页参数
     * @return 分页结果
     */
    PageResult<FileRecordVO> pageByProject(Long projectId, PageQuery query);

    /**
     * 查询项目下全部文件ID（非分页，ID 升序）。
     * <p>
     * 供项目范围检索组装 fileId IN 兜底过滤（Phase 6）：历史 chunk（先入库后绑定项目）
     * 无 projectId metadata，仅能经 fileId 命中。项目无文件时返回空列表。
     * 实时查询当前 file_record.project_id，不缓存（约束 4：解绑后不得再被命中）。
     *
     * @param projectId 项目ID
     * @return 文件ID列表（升序）
     */
    List<Long> listFileIdsByProject(Long projectId);

    /**
     * 将文件关联到项目（一个文件至多归属一个项目）。
     * <p>
     * 文件已归属本项目视为幂等 no-op；已归属其他项目抛 6003。
     *
     * @param projectId 项目ID
     * @param fileId    文件记录ID
     */
    void associateToProject(Long projectId, Long fileId);

    /**
     * 解除文件与项目的关联（project_id 置空，不删除文件）。
     * <p>
     * 文件未归属该项目（null 或其他项目）抛 6004。
     *
     * @param projectId 项目ID
     * @param fileId    文件记录ID
     */
    void dissociateFromProject(Long projectId, Long fileId);
}
