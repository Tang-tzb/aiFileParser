package com.aifp.aiagent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 表单详情展示 VO（含字段列表）
 *
 * @author Tang_tzb
 */
@Data
public class FormVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表单 ID（雪花算法大整数，序列化为字符串避免前端 JS 精度丢失）
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long formId;
    private String formName;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 字段列表，按 sort 升序
     */
    private List<FormFieldVO> fields;
}
