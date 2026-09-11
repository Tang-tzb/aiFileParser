package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ExtractionResult;
import com.aifp.aiagent.dto.FormFieldVO;
import com.aifp.aiagent.entity.Project;
import com.aifp.aiagent.entity.ProjectForm;
import com.aifp.aiagent.entity.ProjectFormFieldValue;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.ProjectFormFieldValueMapper;
import com.aifp.aiagent.repository.ProjectFormMapper;
import com.aifp.aiagent.repository.ProjectMapper;
import com.aifp.aiagent.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectFormPersistenceServiceImpl} 测试
 * <p>
 * 覆盖数据生命周期（补充约束 9）：projectId=null 零写入、抽取成功实例 find-or-create、
 * 同文件重复抽取删旧插新 + version 递增、不同文件互不覆盖、失败不触发持久化由调用方保证、
 * 全空值跳过、守门（403/6001）。Mapper 与 ProjectAccessService 均为 Mockito 模拟，完全离线。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectFormPersistenceServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long FORM_ID = 1785700001L;
    private static final Long FILE_ID = 1785800001L;
    private static final Long OTHER_FILE_ID = 1785800002L;
    private static final Long PROJECT_FORM_ID = 1785600001L;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private ProjectFormMapper projectFormMapper;
    @Mock
    private ProjectFormFieldValueMapper projectFormFieldValueMapper;
    private ProjectFormPersistenceServiceImpl persistenceService;

    /**
     * 离线初始化 MyBatis-Plus 实体元数据：delete wrapper 的 getSqlSegment 断言
     * 需要实体列信息（生产环境由 Mapper 注册自动完成，测试需手动初始化）。
     */
    @BeforeAll
    static void initEntityMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectForm.class);
        TableInfoHelper.initTableInfo(assistant, ProjectFormFieldValue.class);
    }

    @BeforeEach
    void setUp() {
        persistenceService = new ProjectFormPersistenceServiceImpl(
                projectMapper, projectAccessService, projectFormMapper, projectFormFieldValueMapper);
    }

    // ==================== 历史兼容 / 守门 ====================

    /**
     * 补充约束 5：projectId=null 完全跳过 project_form / project_form_field_value 持久化。
     */
    @Test
    void persistExtraction_nullProjectId_zeroWrites() {
        persistenceService.persistExtraction(null, FORM_ID, FILE_ID, fields(), result());

        verifyNoInteractions(projectAccessService, projectMapper, projectFormMapper, projectFormFieldValueMapper);
    }

    /**
     * 权限校验在 Service 层：canAccess=false → FORBIDDEN(403)，零写入。
     */
    @Test
    void persistExtraction_accessDenied_throws403() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(false);

        assertThatThrownBy(() -> persistenceService.persistExtraction(
                PROJECT_ID, FORM_ID, FILE_ID, fields(), result()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verify(projectFormMapper, never()).insert(any(ProjectForm.class));
        verifyNoInteractions(projectFormFieldValueMapper);
    }

    /**
     * 项目不存在 → PROJECT_NOT_FOUND(6001)。
     */
    @Test
    void persistExtraction_projectNotFound_throws6001() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> persistenceService.persistExtraction(
                PROJECT_ID, FORM_ID, FILE_ID, fields(), result()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));
    }

    /**
     * 补充约束 4/9：values 全为 null（或无 values）→ 整体跳过，不建实例不升版本。
     */
    @Test
    void persistExtraction_allValuesNull_skipsEntirely() {
        guardPasses();

        ExtractionResult empty = new ExtractionResult();
        // Map.of 不允许 null 值，用 HashMap 构造含 null 的 values（模拟必填字段缺失）
        Map<String, Object> emptyValues = new HashMap<>();
        emptyValues.put("projectName", null);
        emptyValues.put("amount", null);
        empty.setValues(emptyValues);
        empty.setRawValues(new HashMap<>(emptyValues));
        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, FILE_ID, fields(), empty);

        verify(projectFormMapper, never()).insert(any(ProjectForm.class));
        verify(projectFormMapper, never()).updateById(any(ProjectForm.class));
        verify(projectFormFieldValueMapper, never()).insert(any(ProjectFormFieldValue.class));
    }

    // ==================== 生命周期：find-or-create + 同源替换 ====================

    /**
     * 首次抽取（实例不存在）：创建 project_form——version=1、ACTIVE、sourceFileId=触发文件；
     * 字段值行含定义快照 + raw/normalized + 溯源列。
     */
    @Test
    void persistExtraction_firstTime_createsInstanceWithVersion1() {
        guardPasses();
        when(projectFormMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 模拟数据库自增主键回填，使值行挂到新实例 ID 上
        when(projectFormMapper.insert(any(ProjectForm.class))).thenAnswer(inv -> {
            ProjectForm pf = inv.getArgument(0);
            pf.setId(PROJECT_FORM_ID);
            return 1;
        });

        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, FILE_ID, fields(), result());

        ArgumentCaptor<ProjectForm> formCaptor = ArgumentCaptor.forClass(ProjectForm.class);
        verify(projectFormMapper).insert(formCaptor.capture());
        ProjectForm created = formCaptor.getValue();
        assertThat(created.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(created.getFormId()).isEqualTo(FORM_ID);
        assertThat(created.getSourceFileId()).isEqualTo(FILE_ID);
        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getStatus()).isEqualTo(ProjectFormStatus.ACTIVE);
        assertValueRowInserts(PROJECT_FORM_ID, FILE_ID);
    }

    /**
     * 补充约束 2/9：同文件再次抽取 → 旧值逻辑删除 + 新值插入，version=旧+1（成功持久化代数），
     * sourceFileId 已存在不覆盖。
     */
    @Test
    void persistExtraction_sameFileReExtract_deletesOldAndBumpsVersion() {
        guardPasses();
        ProjectForm existing = existingInstance(2);
        when(projectFormMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, FILE_ID, fields(), result());

        ArgumentCaptor<ProjectForm> formCaptor = ArgumentCaptor.forClass(ProjectForm.class);
        verify(projectFormMapper).updateById(formCaptor.capture());
        ProjectForm updated = formCaptor.getValue();
        assertThat(updated.getVersion()).isEqualTo(3);
        assertThat(updated.getSourceFileId()).isEqualTo(FILE_ID);

        // 替换边界 = projectFormId + sourceFileId：逻辑删旧后插入新行
        verify(projectFormFieldValueMapper).delete(any(LambdaQueryWrapper.class));
        assertValueRowInserts(PROJECT_FORM_ID, FILE_ID);
    }

    /**
     * 补充约束 9/10：不同文件抽取成功 → 只替换本文件旧值，不覆盖前一文件字段值；
     * 新来源文件首次回填实例 sourceFileId 为 null 时才生效。
     */
    @Test
    void persistExtraction_otherFile_doesNotOverwritePreviousFileValues() {
        guardPasses();
        ProjectForm existing = existingInstance(1);
        existing.setSourceFileId(FILE_ID);
        when(projectFormMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, OTHER_FILE_ID, fields(), result());

        ArgumentCaptor<ProjectForm> formCaptor = ArgumentCaptor.forClass(ProjectForm.class);
        verify(projectFormMapper).updateById(formCaptor.capture());
        assertThat(formCaptor.getValue().getSourceFileId()).isEqualTo(FILE_ID);
        // 删除边界锁定在 sourceFileId=OTHER_FILE_ID，FILE_ID 的历史值不被触碰（替换边界语义由 delete wrapper 保证）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ProjectFormFieldValue>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectFormFieldValueMapper).delete(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("source_file_id");
    }

    /**
     * 补充约束 3：rawValue=LLM 原始形态（coerce 前）、normalizedValue=Validator 转换值
     * （BigDecimal toPlainString / LocalDate ISO）、sources 缺引用 → 溯源列 null。
     */
    @Test
    void persistExtraction_valueSemantics_rawNormalizedAndTracing() {
        guardPasses();
        when(projectFormMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ExtractionResult r = result();
        r.setValues(new HashMap<>(Map.of(
                "projectName", "智慧校园",
                "amount", new BigDecimal("5000000"),
                "signDate", LocalDate.of(2026, 1, 15))));
        r.setRawValues(new HashMap<>(Map.of(
                "projectName", "智慧校园",
                "amount", "500万元",
                "signDate", "2026-01-15")));
        r.setSources(Map.of("amount", "chunk-uuid-1"));
        r.setSourcePages(Map.of("amount", 3));
        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, FILE_ID, fields(), r);

        ArgumentCaptor<ProjectFormFieldValue> captor = ArgumentCaptor.forClass(ProjectFormFieldValue.class);
        verify(projectFormFieldValueMapper, times(3)).insert(captor.capture());
        Map<String, ProjectFormFieldValue> byCode = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(ProjectFormFieldValue::getFieldCode, v -> v));

        ProjectFormFieldValue amount = byCode.get("amount");
        assertThat(amount.getRawValue()).isEqualTo("500万元");
        assertThat(amount.getNormalizedValue()).isEqualTo("5000000");
        assertThat(amount.getSourceChunkId()).isEqualTo("chunk-uuid-1");
        assertThat(amount.getSourcePage()).isEqualTo(3);
        assertThat(amount.getFieldName()).isEqualTo("投资金额");
        assertThat(amount.getFieldType()).isEqualTo(FieldType.DECIMAL);
        assertThat(amount.getConfidence()).isNull();

        // projectName 未被引用 → 溯源列 null（禁止猜测）
        ProjectFormFieldValue name = byCode.get("projectName");
        assertThat(name.getRawValue()).isEqualTo("智慧校园");
        assertThat(name.getSourceChunkId()).isNull();
        assertThat(name.getSourcePage()).isNull();

        // LocalDate 标准 ISO 文本
        assertThat(byCode.get("signDate").getNormalizedValue()).isEqualTo("2026-01-15");
    }

    /**
     * 补充约束 4：部分字段成功（values 含 null + errors 非空）→ 仅持久化非空值，
     * errors 由 ExtractionResult 原样保留返回给调用方（持久化层不吞不改）。
     */
    @Test
    void persistExtraction_partialSuccess_persistsNonNullOnly() {
        guardPasses();
        when(projectFormMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ExtractionResult r = result();
        // Map.of 不允许 null 值，用 HashMap 构造部分成功场景；errors 由抽取层填充并原样保留
        Map<String, Object> partialValues = new HashMap<>();
        partialValues.put("projectName", "智慧校园");
        partialValues.put("amount", null);
        r.setValues(partialValues);
        r.setErrors(List.of(new com.aifp.aiagent.dto.FieldError(
                "amount", "MISSING", "必填字段缺失", null)));
        persistenceService.persistExtraction(PROJECT_ID, FORM_ID, FILE_ID, fields(), r);

        ArgumentCaptor<ProjectFormFieldValue> captor = ArgumentCaptor.forClass(ProjectFormFieldValue.class);
        verify(projectFormFieldValueMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getFieldCode()).isEqualTo("projectName");
        // errors 原样保留（持久化不修改结果对象错误语义）
        assertThat(r.getErrors()).isNotEmpty();
    }

    // ==================== 测试辅助 ====================

    /**
     * 守门通过：canAccess=true + 项目存在
     */
    private void guardPasses() {
        when(projectAccessService.canAccess(PROJECT_ID)).thenReturn(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(new Project());
    }

    private ProjectForm existingInstance(int version) {
        ProjectForm pf = new ProjectForm();
        pf.setId(PROJECT_FORM_ID);
        pf.setProjectId(PROJECT_ID);
        pf.setFormId(FORM_ID);
        pf.setVersion(version);
        pf.setStatus(ProjectFormStatus.ACTIVE);
        return pf;
    }

    private List<FormFieldVO> fields() {
        FormFieldVO f1 = new FormFieldVO();
        f1.setFieldId(1L);
        f1.setFieldName("项目名称");
        f1.setFieldCode("projectName");
        f1.setFieldType(FieldType.STRING);
        f1.setRequired(true);
        f1.setSort(1);
        FormFieldVO f2 = new FormFieldVO();
        f2.setFieldId(2L);
        f2.setFieldName("投资金额");
        f2.setFieldCode("amount");
        f2.setFieldType(FieldType.DECIMAL);
        f2.setRequired(true);
        f2.setSort(2);
        FormFieldVO f3 = new FormFieldVO();
        f3.setFieldId(3L);
        f3.setFieldName("签订日期");
        f3.setFieldCode("signDate");
        f3.setFieldType(FieldType.DATE);
        f3.setRequired(false);
        f3.setSort(3);
        return List.of(f1, f2, f3);
    }

    private ExtractionResult result() {
        ExtractionResult r = new ExtractionResult();
        r.setValues(new HashMap<>(Map.of("projectName", "智慧校园", "amount", new BigDecimal("5000000"))));
        r.setRawValues(new HashMap<>(Map.of("projectName", "智慧校园", "amount", "500万元")));
        return r;
    }

    /**
     * 断言：插入的值行挂在 projectFormId 上且 sourceFileId=来源文件
     */
    private void assertValueRowInserts(Long projectFormId, Long sourceFileId) {
        ArgumentCaptor<ProjectFormFieldValue> captor = ArgumentCaptor.forClass(ProjectFormFieldValue.class);
        verify(projectFormFieldValueMapper, atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(row -> {
                    assertThat(row.getProjectFormId()).isEqualTo(projectFormId);
                    assertThat(row.getSourceFileId()).isEqualTo(sourceFileId);
                });
    }
}
