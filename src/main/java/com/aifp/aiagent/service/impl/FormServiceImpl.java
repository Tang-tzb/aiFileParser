package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.FormCreateDTO;
import com.aifp.aiagent.dto.FormFieldCreateDTO;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.dto.FormVO;
import com.aifp.aiagent.entity.FormDefinition;
import com.aifp.aiagent.entity.FormFieldDefinition;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FormDefinitionMapper;
import com.aifp.aiagent.repository.FormFieldDefinitionMapper;
import com.aifp.aiagent.service.FormService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态表单管理服务实现
 *
 * @author aiFileParser
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormServiceImpl implements FormService {

    private final FormDefinitionMapper formDefinitionMapper;
    private final FormFieldDefinitionMapper formFieldDefinitionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createForm(FormCreateDTO dto) {
        FormDefinition form = new FormDefinition();
        form.setFormName(dto.getFormName());
        form.setDescription(dto.getDescription());
        formDefinitionMapper.insert(form);
        Long formId = form.getId();

        List<FormFieldCreateDTO> fields = dto.getFields();
        if (fields != null && !fields.isEmpty()) {
            checkFieldCodeUniqueInBatch(fields);
            int order = 0;
            for (FormFieldCreateDTO f : fields) {
                formFieldDefinitionMapper.insert(toEntity(formId, f, order));
                order++;
            }
        }
        log.info("创建表单成功 formId={}, fieldCount={}", formId,
                fields == null ? 0 : fields.size());
        return formId;
    }

    @Override
    public FormVO getFormById(Long id) {
        FormDefinition form = formDefinitionMapper.selectById(id);
        if (form == null) {
            throw new BusinessException(ResultCode.FORM_NOT_FOUND);
        }
        List<FormFieldDefinition> fields = formFieldDefinitionMapper.selectList(
                new LambdaQueryWrapper<FormFieldDefinition>()
                        .eq(FormFieldDefinition::getFormId, id)
                        .orderByAsc(FormFieldDefinition::getSort));
        return toFormVO(form, fields);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addField(Long formId, FormFieldCreateDTO dto) {
        ensureFormExists(formId);
        ensureFieldCodeNotDuplicate(formId, dto.getFieldCode());
        FormFieldDefinition entity = toEntity(formId, dto, 0);
        formFieldDefinitionMapper.insert(entity);
        log.info("表单追加字段成功 formId={}, fieldId={}, code={}",
                formId, entity.getId(), dto.getFieldCode());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteField(Long formId, Long fieldId) {
        FormFieldDefinition field = formFieldDefinitionMapper.selectById(fieldId);
        if (field == null || !formId.equals(field.getFormId())) {
            throw new BusinessException(ResultCode.FIELD_NOT_FOUND);
        }
        formFieldDefinitionMapper.deleteById(fieldId);
        log.info("删除表单字段成功 formId={}, fieldId={}", formId, fieldId);
    }

    // ==================== 内部方法 ====================

    private void ensureFormExists(Long formId) {
        if (formDefinitionMapper.selectById(formId) == null) {
            throw new BusinessException(ResultCode.FORM_NOT_FOUND);
        }
    }

    private void ensureFieldCodeNotDuplicate(Long formId, String fieldCode) {
        Long count = formFieldDefinitionMapper.selectCount(
                new LambdaQueryWrapper<FormFieldDefinition>()
                        .eq(FormFieldDefinition::getFormId, formId)
                        .eq(FormFieldDefinition::getFieldCode, fieldCode));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.FIELD_CODE_DUPLICATE,
                    "字段编码已存在: " + fieldCode);
        }
    }

    private void checkFieldCodeUniqueInBatch(List<FormFieldCreateDTO> fields) {
        Set<String> codes = new HashSet<>();
        for (FormFieldCreateDTO f : fields) {
            if (!codes.add(f.getFieldCode())) {
                throw new BusinessException(ResultCode.FIELD_CODE_DUPLICATE,
                        "字段编码在请求内重复: " + f.getFieldCode());
            }
        }
    }

    private FormFieldDefinition toEntity(Long formId, FormFieldCreateDTO dto, int fallbackOrder) {
        FormFieldDefinition e = new FormFieldDefinition();
        e.setFormId(formId);
        e.setFieldName(dto.getFieldName());
        e.setFieldCode(dto.getFieldCode());
        e.setFieldType(dto.getFieldType());
        e.setRequired(Boolean.TRUE.equals(dto.getRequired()));
        e.setDescription(dto.getDescription());
        e.setSort(dto.getSort() != null ? dto.getSort() : fallbackOrder);
        return e;
    }

    private FormVO toFormVO(FormDefinition form, List<FormFieldDefinition> fields) {
        FormVO vo = new FormVO();
        vo.setFormId(form.getId());
        vo.setFormName(form.getFormName());
        vo.setDescription(form.getDescription());
        vo.setCreateTime(form.getCreateTime());
        vo.setUpdateTime(form.getUpdateTime());
        List<FormFieldVO> fieldVOs = (fields == null ? Collections.<FormFieldDefinition>emptyList() : fields)
                .stream().map(this::toFieldVO).toList();
        vo.setFields(fieldVOs);
        return vo;
    }

    private FormFieldVO toFieldVO(FormFieldDefinition f) {
        FormFieldVO v = new FormFieldVO();
        v.setFieldId(f.getId());
        v.setFieldName(f.getFieldName());
        v.setFieldCode(f.getFieldCode());
        v.setFieldType(f.getFieldType());
        v.setRequired(f.getRequired());
        v.setDescription(f.getDescription());
        v.setSort(f.getSort());
        return v;
    }
}
