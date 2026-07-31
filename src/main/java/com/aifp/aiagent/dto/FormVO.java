package com.aifp.aiagent.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 表单详情展示 VO（含字段列表）
 *
 * @author aiFileParser
 */
@Data
public class FormVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
