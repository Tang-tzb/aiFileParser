package com.aifp.aiagent.dto;

import com.aifp.aiagent.entity.enums.FileStatus;
import com.aifp.aiagent.entity.enums.FileType;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件上传结果 VO
 *
 * @author Tang_tzb
 */
@Data
public class FileUploadVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;
    private String fileName;
    private FileType fileType;
    private String filePath;
    private FileStatus status;
    private LocalDateTime createTime;
}
