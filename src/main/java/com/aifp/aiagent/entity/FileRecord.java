package com.aifp.aiagent.entity;

import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件记录实体
 * <p>
 * 对应表 file_record：上传文件的元数据与处理状态。
 * 后续解析阶段通过 {@link FileStatus} 流转状态，并据 {@link FileType} 选择 Parser。
 *
 * @author aiFileParser
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_record")
public class FileRecord extends BaseEntity {

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件类型
     */
    private FileType fileType;

    /**
     * 存储相对路径（相对 upload-dir，如 2026/07/uuid.pdf）
     */
    private String filePath;

    /**
     * 处理状态
     */
    private FileStatus status;
}
