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
 * 文件记录展示 VO（完整记录，供查询/后续阶段复用）
 *
 * @author Tang_tzb
 */
@Data
public class FileRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;
    private String fileName;
    private FileType fileType;
    private String filePath;

    /**
     * 所属项目ID（可空，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;
    private FileStatus status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
