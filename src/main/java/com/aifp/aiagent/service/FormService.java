package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.*;

/**
 * 动态表单管理服务
 *
 * @author Tang_tzb
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
     * 分页查询表单列表（按 createTime DESC）。
     * <p>
     * 列表场景仅返回表单元数据，{@code fields} 置空避免 N+1 查询；
     * 字段详情请走 {@link #getFormById(Long)}。
     *
     * @param query 分页参数
     * @return 分页结果（每条为 FormVO，fields 为空列表）
     */
    PageResult<FormVO> page(PageQuery query);

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

    /**
     * 删除表单（软删除表单元数据 + 级联软删除其下所有字段）。
     *
     * @param id 表单ID
     */
    void deleteForm(Long id);
}
