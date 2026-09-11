package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.entity.ProjectForm;
import com.aifp.aiagent.entity.ProjectFormFieldValue;
import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.ProjectFormFieldValueMapper;
import com.aifp.aiagent.repository.ProjectFormMapper;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.ProjectFormPersistenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目表单持久化服务实现
 * <p>
 * 事务边界：find-or-create 实例 + 删旧值 + 插新值 + 版本递增在同一事务，
 * 任一失败整体回滚（version 只随成功持久化递增，约束 2）。
 * 异常语义：仅透出本层数据库写入异常，不捕获/转换调用方原有异常（约束 8）。
 * <p>
 * 三值语义（约束 3）：rawValue=LLM 原始返回、normalizedValue=Validator 转换值、
 * sourceChunkId/sourcePage=LLM 引用的 Chunk 身份与 metadata，缺引用存 null。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFormPersistenceServiceImpl implements ProjectFormPersistenceService {

    private final ProjectMapper projectMapper;
    private final ProjectAccessService projectAccessService;
    private final ProjectFormMapper projectFormMapper;
    private final ProjectFormFieldValueMapper projectFormFieldValueMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistExtraction(Long projectId, Long formId, Long fileId,
                                  List<FormFieldVO> fields, ExtractionResult result) {
        // 历史兼容：projectId 为 null 完全跳过项目域持久化（约束 5）
        if (projectId == null) {
            return;
        }
        ensureProjectGuard(projectId);
        Map<String, Object> values = result == null ? null : result.getValues();
        // 仅非空值可持久化；全空值整体跳过：不建实例、不删行、不升版本（约束 4）
        List<String> persistableCodes = persistableCodes(values);
        if (persistableCodes.isEmpty()) {
            log.info("抽取无可持久化字段值，跳过项目域写入 projectId={}, formId={}, fileId={}",
                    projectId, formId, fileId);
            return;
        }
        ProjectForm projectForm = resolveProjectForm(projectId, formId, fileId);
        replaceSourceValues(projectForm.getId(), fileId, persistableCodes, fields, result);
        log.info("项目字段值持久化完成 projectFormId={}, version={}, fileId={}, 字段数={}",
                projectForm.getId(), projectForm.getVersion(), fileId, persistableCodes.size());
    }

    // ==================== 内部方法 ====================

    /**
     * 项目访问权限 + 项目存在性守门（权限判断唯一入口，Controller 不感知权限）
     */
    private void ensureProjectGuard(Long projectId) {
        if (!projectAccessService.canAccess(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        }
    }

    /**
     * 过滤可持久化的非空字段值编码
     */
    private List<String> persistableCodes(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * find-or-create 项目表单实例（uk_project_form 同一 (projectId, formId) 至多一条）：
     * 新建 version=1 且 sourceFileId=触发文件；已存在 version+1，sourceFileId 仅 null 时首次回填。
     */
    private ProjectForm resolveProjectForm(Long projectId, Long formId, Long fileId) {
        ProjectForm projectForm = projectFormMapper.selectOne(
                new LambdaQueryWrapper<ProjectForm>()
                        .eq(ProjectForm::getProjectId, projectId)
                        .eq(ProjectForm::getFormId, formId));
        if (projectForm == null) {
            projectForm = new ProjectForm();
            projectForm.setProjectId(projectId);
            projectForm.setFormId(formId);
            projectForm.setSourceFileId(fileId);
            projectForm.setVersion(1);
            projectForm.setStatus(ProjectFormStatus.ACTIVE);
            projectFormMapper.insert(projectForm);
            return projectForm;
        }
        projectForm.setVersion(projectForm.getVersion() + 1);
        if (projectForm.getSourceFileId() == null) {
            projectForm.setSourceFileId(fileId);
        }
        projectFormMapper.updateById(projectForm);
        return projectForm;
    }

    /**
     * 同源替换：按 (projectFormId, sourceFileId) 逻辑删除旧值后插入新值。
     * 不触碰其他来源文件的字段值（约束 10：多来源并存合法）。
     */
    private void replaceSourceValues(Long projectFormId, Long fileId, List<String> codes,
                                     List<FormFieldVO> fields, ExtractionResult result) {
        projectFormFieldValueMapper.delete(new LambdaQueryWrapper<ProjectFormFieldValue>()
                .eq(ProjectFormFieldValue::getProjectFormId, projectFormId)
                .eq(ProjectFormFieldValue::getSourceFileId, fileId));
        Map<String, FormFieldVO> fieldByCode = fields.stream()
                .collect(Collectors.toMap(FormFieldVO::getFieldCode, Function.identity()));
        for (String code : codes) {
            projectFormFieldValueMapper.insert(
                    buildValueRow(projectFormId, fileId, code, fieldByCode.get(code), result));
        }
    }

    /**
     * 组装单行字段值：定义快照 + raw/normalized/溯源列（无引用来源存 null，约束 3）。
     */
    private ProjectFormFieldValue buildValueRow(Long projectFormId, Long fileId, String code,
                                                FormFieldVO field, ExtractionResult result) {
        ProjectFormFieldValue row = new ProjectFormFieldValue();
        row.setProjectFormId(projectFormId);
        if (field != null) {
            row.setFieldId(field.getFieldId());
            row.setFieldName(field.getFieldName());
            row.setFieldType(field.getFieldType());
        }
        row.setFieldCode(code);
        row.setRawValue(toRawString(result.getRawValues(), code));
        row.setNormalizedValue(normalizeValue(result.getValues().get(code)));
        row.setSourceFileId(fileId);
        row.setSourceChunkId(result.getSources() == null ? null : result.getSources().get(code));
        row.setSourcePage(result.getSourcePages() == null ? null : result.getSourcePages().get(code));
        // confidence：LLM 未输出置信度，杜撰违反事实语义，本阶段恒 null
        row.setConfidence(null);
        return row;
    }

    /**
     * LLM 原始值转字符串快照（保留 coerce 前形态，如 "12.5亿元"）
     */
    private String toRawString(Map<String, Object> rawValues, String code) {
        Object raw = rawValues == null ? null : rawValues.get(code);
        return raw == null ? null : String.valueOf(raw);
    }

    /**
     * 标准化值：BigDecimal 用 toPlainString 避免科学计数法；
     * LocalDate 自动 ISO（toString）、Boolean/Integer/String 原样文本化。
     */
    private String normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }
}
