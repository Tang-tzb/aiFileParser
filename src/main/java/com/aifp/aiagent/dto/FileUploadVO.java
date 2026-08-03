package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件上传结果 VO
 *
 * @author aiFileParser
 */
@Data
public class FileUploadVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long fileId;
    private String fileName;
    private FileType fileType;
    private String filePath;
    private FileStatus status;
    private LocalDateTime createTime;
}
