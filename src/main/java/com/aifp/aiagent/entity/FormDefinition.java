package com.aifp.aiagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单定义实体
 * <p>
 * 对应表 form_definition：用户自定义的业务表单元信息（如"项目申报表"）。
 *
 * @author aiFileParser
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("form_definition")
public class FormDefinition extends BaseEntity {

    /**
     * 表单名称
     */
    private String formName;

    /**
     * 表单描述
     */
    private String description;
}
