package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.FormCreateDTO;
import com.aifp.aiagent.dto.FormFieldCreateDTO;
import com.aifp.aiagent.dto.FormVO;

/**
 * 动态表单管理服务
 *
 * @author aiFileParser
 */
public interface FormService {

    /**
     * 创建表单（表单头 + 可选字段列表，同事务）。
     *
     * @param dto 表单创建数据
     * @return 新建表单ID
     */
    Long createForm(FormCreateDTO dto);

    /**
     * 查询表单详情（含按 sort 升序的字段列表）。
     *
     * @param id 表单ID
     * @return 表单详情 VO
     */
    FormVO getFormById(Long id);

    /**
     * 向已有表单追加一个字段。
     *
     * @param formId 表单ID
     * @param dto    字段数据
     * @return 新建字段ID
     */
    Long addField(Long formId, FormFieldCreateDTO dto);

    /**
     * 删除表单下的指定字段（软删除）。
     *
     * @param formId  表单ID
     * @param fieldId 字段ID
     */
    void deleteField(Long formId, Long fieldId);
}
