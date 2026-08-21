package com.aifp.aiagent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 异步任务启动请求
 * <p>
 * 绑定一个表单定义与一份已上传文件，触发后台解析→向量化→AI 抽取流水线。
 *
 * @author Tang_tzb
 */
@Data
public class TaskStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表单 ID（决定 AI 抽取的字段定义）
     */
    @NotNull(message = "表单ID不能为空")
    private Long formId;

    /**
     * 文件记录 ID（已通过 /file/upload 上传）
     */
    @NotNull(message = "文件ID不能为空")
    private Long fileId;
}
