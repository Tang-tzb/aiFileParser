package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件记录展示 VO（完整记录，供查询/后续阶段复用）
 *
 * @author aiFileParser
 */
@Data
public class FileRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long fileId;
    private String fileName;
    private FileType fileType;
    private String filePath;
    private FileStatus status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
