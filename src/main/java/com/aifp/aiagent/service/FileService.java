package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.FileRecordVO;
import com.aifp.aiagent.dto.FileUploadVO;
import com.aifp.aiagent.entity.enums.FileStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件管理服务
 * <p>
 * 负责文件上传、类型判断、记录保存与状态管理。
 * 不与 AI 解析耦合；状态流转方法供阶段 4/5/6 调用。
 *
 * @author aiFileParser
 */
public interface FileService {

    /**
     * 上传文件：保存到存储 + 写入 file_record（状态 UPLOADED）。
     *
     * @param file 上传文件
     * @return 上传结果 VO
     */
    FileUploadVO upload(MultipartFile file);

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
}
