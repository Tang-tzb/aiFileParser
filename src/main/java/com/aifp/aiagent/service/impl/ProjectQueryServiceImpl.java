package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import com.aifp.aiagent.dto.ProjectVO;
import com.aifp.aiagent.entity.FileRecord;
import com.aifp.aiagent.entity.ProjectForm;
import com.aifp.aiagent.entity.ProjectFormFieldValue;
import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.aifp.aiagent.repository.FileRecordMapper;
import com.aifp.aiagent.repository.ProjectFormFieldValueMapper;
import com.aifp.aiagent.repository.ProjectFormMapper;
import com.aifp.aiagent.service.FieldValueConflictResolver;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目结构化事实查询服务实现（Phase 7）
 * <p>
 * 执行序：ProjectService 守门（403/6001）→ 查 ACTIVE 表单实例 →
 * 一次批量查全量字段值（{@code @TableLogic} 自动过滤已删行，约束 2）→
 * 按 {@code projectFormId → fieldCode} 分组 → Resolver 纯函数判冲突 →
 * 单次批量解析来源文件名（缺失置 null，约束 9）→ 组装 VO。
 * <p>
 * 扩展点（约束 1）：分组组织已按 {@code (projectFormId, fieldCode)} 落位，
 * 后续 fieldCode 精查仅需增加查询维度参数与 wrapper 条件，不影响现有结构。
 * <p>
 * 不裁决（约束 5）：version 仅透出展示，不参与冲突判定与取值优先级；
 * 不调用任何 LLM 组件（约束 7）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectQueryServiceImpl implements ProjectQueryService {

    private final ProjectService projectService;
    private final ProjectFormMapper projectFormMapper;
    private final ProjectFormFieldValueMapper fieldValueMapper;
    private final FileRecordMapper fileRecordMapper;
    private final FieldValueConflictResolver conflictResolver;

    @Override
    public ProjectStructuredFactsVO queryProjectFacts(Long projectId) {
        Objects.requireNonNull(projectId, "projectId 不可为空");
        // 权限/存在性守门唯一入口（403/6001），不在此重复实现访问控制
        ProjectVO project = projectService.getProjectById(projectId);

        ProjectStructuredFactsVO result = new ProjectStructuredFactsVO();
        result.setProjectId(projectId);
        result.setProjectName(project.getProjectName());

        // 仅 ACTIVE 实例参与（约束 2/10：ARCHIVED 完全不进入结果）
        List<ProjectForm> activeForms = projectFormMapper.selectList(
                new LambdaQueryWrapper<ProjectForm>()
                        .eq(ProjectForm::getProjectId, projectId)
                        .eq(ProjectForm::getStatus, ProjectFormStatus.ACTIVE)
                        .orderByAsc(ProjectForm::getId));
        if (activeForms.isEmpty()) {
            return result;
        }

        // 全量字段值一次查尽（逻辑删除行由 @TableLogic 自动过滤）；id 升序保证输出稳定
        List<Long> formIds = activeForms.stream().map(ProjectForm::getId).toList();
        List<ProjectFormFieldValue> allValues = fieldValueMapper.selectList(
                new LambdaQueryWrapper<ProjectFormFieldValue>()
                        .in(ProjectFormFieldValue::getProjectFormId, formIds)
                        .orderByAsc(ProjectFormFieldValue::getId));

        // 来源文件名单次批量解析：缺失文件不阻断查询，仅 sourceFileName=null（约束 9）
        Map<Long, String> fileNameMap = resolveFileNames(allValues);

        // 按实例分组（约束 8：不同 ProjectForm 互为独立事实边界）
        Map<Long, List<ProjectFormFieldValue>> valuesByForm = allValues.stream()
                .collect(Collectors.groupingBy(ProjectFormFieldValue::getProjectFormId,
                        LinkedHashMap::new, Collectors.toList()));

        for (ProjectForm form : activeForms) {
            result.getForms().add(buildForm(form,
                    valuesByForm.getOrDefault(form.getId(), List.of()), fileNameMap));
        }
        log.info("项目结构化事实查询完成 projectId={}, forms={}, values={}",
                projectId, activeForms.size(), allValues.size());
        return result;
    }

    @Override
    public List<ProjectFieldDictionaryVO> queryFieldDictionary(List<Long> projectIds) {
        List<ProjectVO> projects = guardProjects(projectIds);
        List<ProjectForm> activeForms = selectActiveForms(
                projects.stream().map(ProjectVO::getProjectId).toList());
        if (activeForms.isEmpty()) {
            return List.of();
        }
        List<ProjectFormFieldValue> allValues = selectValuesByForms(
                activeForms.stream().map(ProjectForm::getId).toList(), null);
        // 字段快照去重：form id 升序（值行已按 projectFormId,id 升序）→ 每个 fieldCode 第一行定义即字典项
        Map<String, ProjectFieldDictionaryVO> dictionary = new LinkedHashMap<>();
        for (ProjectFormFieldValue row : allValues) {
            dictionary.putIfAbsent(row.getFieldCode(), new ProjectFieldDictionaryVO(
                    row.getFieldCode(), row.getFieldName(), row.getFieldType().getCode()));
        }
        return List.copyOf(dictionary.values());
    }

    @Override
    public List<ProjectFieldFactVO> queryFieldFacts(List<Long> projectIds, String fieldCode) {
        if (fieldCode == null || fieldCode.isBlank()) {
            throw new IllegalArgumentException("fieldCode 不可为空白");
        }
        List<ProjectVO> projects = guardProjects(projectIds);
        // projectId → projectName（跨项目比较展示用）
        Map<Long, String> projectNameById = projects.stream().collect(
                Collectors.toMap(ProjectVO::getProjectId, ProjectVO::getProjectName, (a, b) -> a));
        List<ProjectForm> activeForms = selectActiveForms(
                projects.stream().map(ProjectVO::getProjectId).toList());
        List<ProjectFieldFactVO> result = new ArrayList<>();
        if (activeForms.isEmpty()) {
            return result;
        }
        // 单字段精查：仅该 fieldCode 的值行（事实单元 = (projectId, projectFormId, fieldCode)，追加约束 4）
        List<ProjectFormFieldValue> rows = selectValuesByForms(
                activeForms.stream().map(ProjectForm::getId).toList(), fieldCode);
        Map<Long, String> fileNameMap = resolveFileNames(rows);
        Map<Long, List<ProjectFormFieldValue>> rowsByForm = rows.stream().collect(
                Collectors.groupingBy(ProjectFormFieldValue::getProjectFormId,
                        LinkedHashMap::new, Collectors.toList()));
        // 实例无值也输出空结构单元（不省略，供"项目X 未提供该字段数据"解释与无据判定）
        for (ProjectForm form : activeForms) {
            result.add(buildFactUnit(form,
                    projectNameById.get(form.getProjectId()), fieldCode,
                    rowsByForm.getOrDefault(form.getId(), List.of()), fileNameMap));
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 跨项目参数防御 + 逐项目守门：projectIds 非空、不含 null 元素（Phase 6 pattern，
     * 追加约束 9）；每个项目经 ProjectService 守门（403/6001）后返回 VO（含项目名）。
     */
    private List<ProjectVO> guardProjects(List<Long> projectIds) {
        // 含 null 判定必须用流式 anyMatch：List.of 不可变列表 contains(null) 直接 NPE，
        // 会使调用方无法得到约定的 IAE（防御逻辑自身不能成为异常源）
        if (projectIds == null || projectIds.isEmpty()
                || projectIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("projectIds 不可为空或含 null 元素");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>(projectIds);
        List<ProjectVO> projects = new ArrayList<>(distinctIds.size());
        for (Long id : distinctIds) {
            // 权限/存在性守门唯一入口（403/6001），不在此重复实现访问控制
            projects.add(projectService.getProjectById(id));
        }
        return projects;
    }

    /**
     * 批量查询 ACTIVE 表单实例（id 升序，输出稳定；约束 2/10）
     */
    private List<ProjectForm> selectActiveForms(List<Long> projectIds) {
        return projectFormMapper.selectList(new LambdaQueryWrapper<ProjectForm>()
                .in(ProjectForm::getProjectId, projectIds)
                .eq(ProjectForm::getStatus, ProjectFormStatus.ACTIVE)
                .orderByAsc(ProjectForm::getId));
    }

    /**
     * 按 formIds 批量查值行（{@code @TableLogic} 自动过滤已删行）；
     * fieldCode 非空时单字段精查（Phase 10 追加约束 4），null 时全量（字典去重用）。
     * 排序 projectFormId,id 升序保证字典"form id 升序第一为准"语义。
     */
    private List<ProjectFormFieldValue> selectValuesByForms(List<Long> formIds, String fieldCode) {
        LambdaQueryWrapper<ProjectFormFieldValue> wrapper = new LambdaQueryWrapper<ProjectFormFieldValue>()
                .in(ProjectFormFieldValue::getProjectFormId, formIds);
        if (fieldCode != null) {
            wrapper.eq(ProjectFormFieldValue::getFieldCode, fieldCode);
        }
        wrapper.orderByAsc(ProjectFormFieldValue::getProjectFormId)
                .orderByAsc(ProjectFormFieldValue::getId);
        return fieldValueMapper.selectList(wrapper);
    }

    /**
     * 组装跨项目字段事实单元：实例无值时 fieldName/fieldType 置 null
     * （无定义快照可引用），交由计算层按"无数据"处理（追加约束 12）。
     */
    private ProjectFieldFactVO buildFactUnit(ProjectForm form, String projectName,
                                             String fieldCode, List<ProjectFormFieldValue> formRows,
                                             Map<Long, String> fileNameMap) {
        ProjectFieldFactVO unit = new ProjectFieldFactVO();
        unit.setProjectId(form.getProjectId());
        unit.setProjectName(projectName);
        unit.setProjectFormId(form.getId());
        unit.setFieldCode(fieldCode);
        if (!formRows.isEmpty()) {
            ProjectFormFieldValue firstRow = formRows.get(0);
            unit.setFieldName(firstRow.getFieldName());
            unit.setFieldType(firstRow.getFieldType().getCode());
            unit.setConflict(conflictResolver.hasConflict(formRows));
            formRows.forEach(row -> unit.getValues().add(toValueItem(row, fileNameMap)));
        }
        return unit;
    }

    /**
     * 组装单个表单实例 VO：字段值按 fieldCode 分组（LinkedHashMap 保持写入顺序），
     * 同实例内同 fieldCode 聚合为一个 Field 并交由 Resolver 判冲突。
     */
    private ProjectStructuredFactsVO.Form buildForm(ProjectForm form,
                                                    List<ProjectFormFieldValue> formValues,
                                                    Map<Long, String> fileNameMap) {
        ProjectStructuredFactsVO.Form formVO = new ProjectStructuredFactsVO.Form();
        formVO.setProjectFormId(form.getId());
        formVO.setFormId(form.getFormId());
        // version 仅展示（约束 5：不参与冲突裁决）
        formVO.setVersion(form.getVersion());
        formVO.setStatus(form.getStatus().getCode());

        Map<String, List<ProjectFormFieldValue>> valuesByField = formValues.stream()
                .collect(Collectors.groupingBy(ProjectFormFieldValue::getFieldCode,
                        LinkedHashMap::new, Collectors.toList()));
        valuesByField.forEach((fieldCode, rows) ->
                formVO.getFields().add(buildField(rows.get(0), rows, fileNameMap)));
        return formVO;
    }

    /**
     * 组装单个字段事实 VO：冲突标记 + 全部来源值完整返回（约束 3）。
     */
    private ProjectStructuredFactsVO.Field buildField(ProjectFormFieldValue firstRow,
                                                      List<ProjectFormFieldValue> rows,
                                                      Map<Long, String> fileNameMap) {
        ProjectStructuredFactsVO.Field fieldVO = new ProjectStructuredFactsVO.Field();
        fieldVO.setProjectFormId(firstRow.getProjectFormId());
        fieldVO.setFieldId(firstRow.getFieldId());
        fieldVO.setFieldCode(firstRow.getFieldCode());
        fieldVO.setFieldName(firstRow.getFieldName());
        fieldVO.setFieldType(firstRow.getFieldType().getCode());
        fieldVO.setConflict(conflictResolver.hasConflict(rows));
        rows.forEach(row -> fieldVO.getValues().add(toValueItem(row, fileNameMap)));
        return fieldVO;
    }

    /**
     * 值行 → 来源值投影：全部来源元数据照常保留（约束 3/9）。
     */
    private ProjectStructuredFactsVO.ValueItem toValueItem(ProjectFormFieldValue row,
                                                           Map<Long, String> fileNameMap) {
        ProjectStructuredFactsVO.ValueItem item = new ProjectStructuredFactsVO.ValueItem();
        item.setRawValue(row.getRawValue());
        item.setNormalizedValue(row.getNormalizedValue());
        item.setUnit(row.getUnit());
        item.setSourceFileId(row.getSourceFileId());
        item.setSourceFileName(fileNameMap.get(row.getSourceFileId()));
        item.setSourcePage(row.getSourcePage());
        item.setSourceChunkId(row.getSourceChunkId());
        item.setConfidence(row.getConfidence());
        return item;
    }

    /**
     * 批量解析来源文件名：一次 selectBatchIds；文件记录缺失（已删除等）不进 map，
     * 取值侧自然得到 null，不影响其余字段与整体查询（约束 9）。
     */
    private Map<Long, String> resolveFileNames(List<ProjectFormFieldValue> allValues) {
        Set<Long> fileIds = allValues.stream()
                .map(ProjectFormFieldValue::getSourceFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (fileIds.isEmpty()) {
            // 可容纳 null key 查询（HashMap.get(null) 返回 null），与 Map.of 行为不同
            return new HashMap<>();
        }
        Map<Long, String> fileNameMap = new HashMap<>();
        for (FileRecord record : fileRecordMapper.selectBatchIds(fileIds)) {
            fileNameMap.put(record.getId(), record.getFileName());
        }
        return fileNameMap;
    }
}
