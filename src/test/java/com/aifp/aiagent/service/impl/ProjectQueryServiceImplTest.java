package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import com.aifp.aiagent.dto.ProjectVO;
import com.aifp.aiagent.entity.FileRecord;
import com.aifp.aiagent.entity.ProjectForm;
import com.aifp.aiagent.entity.ProjectFormFieldValue;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.entity.enums.ProjectFormStatus;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.repository.FileRecordMapper;
import com.aifp.aiagent.repository.ProjectFormFieldValueMapper;
import com.aifp.aiagent.repository.ProjectFormMapper;
import com.aifp.aiagent.service.FieldValueConflictResolver;
import com.aifp.aiagent.service.ProjectService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectQueryServiceImpl} 测试（离线，Phase 7）
 * <p>
 * 重点覆盖补充约束 10 的五个跨来源场景：同实例同字段两文件不同值 → conflict=true；
 * 同值 → conflict=false；旧值已逻辑删除不参与；不同 ProjectForm 同 fieldCode 不互相判冲突；
 * ARCHIVED 实例完全不进入结果（经 wrapper 条件断言）。另覆盖约束 1（全量查询集中 Service）、
 * 约束 3（冲突完整保留来源）、约束 9（来源文件缺失不影响查询）、守门 403/6001。
 * Mapper 均为 Mockito 模拟，{@code @TableLogic} 已删行由模拟侧还原 DB 行为（不返回）。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectQueryServiceImplTest {

    private static final Long PROJECT_ID = 1785900001L;
    private static final Long FORM_ID = 1785700001L;
    private static final Long PROJECT_FORM_ID = 1785600001L;
    private static final Long PROJECT_FORM_ID_2 = 1785600002L;
    private static final Long FILE_A = 1785800001L;
    private static final Long FILE_B = 1785800002L;
    private static final String FIELD_AMOUNT = "total_investment";

    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectFormMapper projectFormMapper;
    @Mock
    private ProjectFormFieldValueMapper fieldValueMapper;
    @Mock
    private FileRecordMapper fileRecordMapper;

    private ProjectQueryServiceImpl queryService;

    /**
     * 离线初始化实体元数据：捕获 LambdaQueryWrapper 断言 status=ACTIVE 条件
     * 需要实体列信息（生产环境由 Mapper 注册自动完成）
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
        // Resolver 为纯函数组件，使用真实实例验证端到端判定语义
        queryService = new ProjectQueryServiceImpl(
                projectService, projectFormMapper, fieldValueMapper,
                fileRecordMapper, new FieldValueConflictResolver());
    }

    // ==================== 约束 10 场景：跨来源冲突判定 ====================

    /**
     * 场景 1：同一 ProjectForm + 同 fieldCode + 两个不同文件 + 不同 normalizedValue
     * → conflict=true，且约束 3：两条来源值完整保留（含文件名解析）
     */
    @Test
    void sameFormSameField_twoFilesDifferentValues_conflictWithAllSources() {
        activeFormStubs();
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_A),
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "200万", "2000000", FILE_B)));
        when(fileRecordMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                fileRecord(FILE_A, "预算说明书.pdf"),
                fileRecord(FILE_B, "可研报告.pdf")));

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        assertThat(result.getForms()).hasSize(1);
        ProjectStructuredFactsVO.Field field = result.getForms().get(0).getFields().get(0);
        assertThat(field.isConflict()).isTrue();
        assertThat(field.getValues()).hasSize(2);
        // 约束 3：conflict=true 不得丢失任何来源信息
        ProjectStructuredFactsVO.ValueItem first = field.getValues().get(0);
        assertThat(first.getRawValue()).isEqualTo("100万");
        assertThat(first.getNormalizedValue()).isEqualTo("1000000");
        assertThat(first.getUnit()).isEqualTo("万元");
        assertThat(first.getSourceFileId()).isEqualTo(FILE_A);
        assertThat(first.getSourceFileName()).isEqualTo("预算说明书.pdf");
        assertThat(first.getSourcePage()).isEqualTo(3);
        assertThat(first.getSourceChunkId()).isEqualTo("chunk-" + FILE_A);
        assertThat(first.getConfidence()).isEqualByComparingTo("0.95");
        assertThat(field.getValues().get(1).getSourceFileName()).isEqualTo("可研报告.pdf");
    }

    /**
     * 场景 2：同一 ProjectForm + 同 fieldCode + 两个不同文件 + 相同 normalizedValue
     * → conflict=false；但两条来源值仍完整返回（不因无冲突而裁剪）
     */
    @Test
    void sameFormSameField_twoFilesSameValue_noConflictButAllValuesKept() {
        activeFormStubs();
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_A),
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_B)));
        when(fileRecordMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        ProjectStructuredFactsVO.Field field = result.getForms().get(0).getFields().get(0);
        assertThat(field.isConflict()).isFalse();
        assertThat(field.getValues()).hasSize(2);
        // 约束 9：文件记录查询不到 → sourceFileName=null，其余照常
        assertThat(field.getValues().get(0).getSourceFileName()).isNull();
        assertThat(field.getValues().get(0).getSourceFileId()).isEqualTo(FILE_A);
    }

    /**
     * 场景 3：旧值已逻辑删除 → 不参与冲突。
     * 模拟侧还原 {@code @TableLogic} DB 行为：已删行不会出现在 selectList 结果中，
     * Service 收到的即"未逻辑删除 + ACTIVE"有效值（约束 2），单条新值无冲突
     */
    @Test
    void deletedOldValue_notReturnedByMapper_notParticipatingInConflict() {
        activeFormStubs();
        // DB 中旧值（FILE_A，normalizedValue=900000）已逻辑删除——查询侧自动过滤，仅返回新值
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_B)));
        when(fileRecordMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        ProjectStructuredFactsVO.Field field = result.getForms().get(0).getFields().get(0);
        assertThat(field.isConflict()).isFalse();
        assertThat(field.getValues()).hasSize(1);
        assertThat(field.getValues().get(0).getNormalizedValue()).isEqualTo("1000000");
    }

    /**
     * 场景 4：不同 ProjectForm + 相同 fieldCode → 不互相判冲突、不合并。
     * 事实边界 = projectFormId + fieldCode（约束 8）：两个实例各自独立的 Field
     */
    @Test
    void crossFormSameFieldCode_notMergedAndNoCrossConflict() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(projectFormMapper.selectList(any())).thenReturn(List.of(
                form(PROJECT_FORM_ID), form(PROJECT_FORM_ID_2)));
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_A),
                value(PROJECT_FORM_ID_2, FIELD_AMOUNT, "200万", "2000000", FILE_B)));
        when(fileRecordMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        assertThat(result.getForms()).hasSize(2);
        for (ProjectStructuredFactsVO.Form formVO : result.getForms()) {
            assertThat(formVO.getFields()).hasSize(1);
            ProjectStructuredFactsVO.Field field = formVO.getFields().get(0);
            // 同 fieldCode 但分属不同实例：各自单值，互不判冲突
            assertThat(field.getFieldCode()).isEqualTo(FIELD_AMOUNT);
            assertThat(field.isConflict()).isFalse();
            assertThat(field.getValues()).hasSize(1);
        }
        // 事实边界保留：两个 projectFormId 各自独立可区分
        assertThat(result.getForms().get(0).getProjectFormId()).isEqualTo(PROJECT_FORM_ID);
        assertThat(result.getForms().get(1).getProjectFormId()).isEqualTo(PROJECT_FORM_ID_2);
    }

    /**
     * 场景 5：ARCHIVED ProjectForm 完全不进入结果。
     * 捕获实例查询 wrapper 断言查询条件即 project_id + status=ACTIVE
     * （归档实例在 DB 侧即被排除，Service 不做二次过滤）
     */
    @Test
    @SuppressWarnings("unchecked")
    void archivedForm_excludedByActiveStatusQueryCondition() {
        activeFormStubs();
        when(fieldValueMapper.selectList(any())).thenReturn(List.of());
        queryService.queryProjectFacts(PROJECT_ID);

        ArgumentCaptor<LambdaQueryWrapper<ProjectForm>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectFormMapper).selectList(captor.capture());
        LambdaQueryWrapper<ProjectForm> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("project_id").contains("status");
        // status 参数为 ProjectFormStatus 枚举实例（@EnumValue 持久化），统一按字符串断言
        assertThat(wrapper.getParamNameValuePairs().values())
                .extracting(String::valueOf)
                .contains(String.valueOf(PROJECT_ID), "ACTIVE");
    }

    // ==================== 结果组装与容错（约束 1/9） ====================

    /**
     * 无 ACTIVE 实例：返回空 forms 空结构（非错误），且不触发字段值/文件查询
     */
    @Test
    void noActiveForms_returnsEmptyStructureWithoutValueQuery() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(projectFormMapper.selectList(any())).thenReturn(List.of());

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        assertThat(result.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(result.getProjectName()).isEqualTo("测试项目");
        assertThat(result.getForms()).isEmpty();
        verifyNoInteractions(fieldValueMapper, fileRecordMapper);
    }

    /**
     * 实例存在但无字段值：Form 骨架照常返回（空 fields）
     */
    @Test
    void formWithoutValues_formShellStillReturned() {
        activeFormStubs();
        when(fieldValueMapper.selectList(any())).thenReturn(List.of());

        ProjectStructuredFactsVO result = queryService.queryProjectFacts(PROJECT_ID);

        assertThat(result.getForms()).hasSize(1);
        assertThat(result.getForms().get(0).getFields()).isEmpty();
        assertThat(result.getForms().get(0).getVersion()).isEqualTo(1);
        verifyNoInteractions(fileRecordMapper);
    }

    /**
     * 约束 9：来源文件缺失（含 sourceFileId 为 null 的行）不影响整条查询——
     * sourceFileName=null，rawValue/normalizedValue/sourceFileId/page/chunkId 完整保留
     */
    @Test
    void missingSourceRecord_queryStillSucceedsWithNullFileName() {
        activeFormStubs();

        // 第一步：sourceFileId 为 null 的行 → 不发起批量文件查询，值完整保留
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", null)));

        ProjectStructuredFactsVO first = queryService.queryProjectFacts(PROJECT_ID);
        ProjectStructuredFactsVO.ValueItem orphanItem =
                first.getForms().get(0).getFields().get(0).getValues().get(0);
        assertThat(orphanItem.getSourceFileId()).isNull();
        assertThat(orphanItem.getSourceFileName()).isNull();
        assertThat(orphanItem.getNormalizedValue()).isEqualTo("1000000");
        verifyNoInteractions(fileRecordMapper);

        // 第二步：sourceFileId 指向已删除文件（selectBatchIds 查不到）→ 同样不阻断查询
        when(fieldValueMapper.selectList(any())).thenReturn(List.of(
                value(PROJECT_FORM_ID, FIELD_AMOUNT, "100万", "1000000", FILE_B)));
        when(fileRecordMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        ProjectStructuredFactsVO second = queryService.queryProjectFacts(PROJECT_ID);
        ProjectStructuredFactsVO.ValueItem item =
                second.getForms().get(0).getFields().get(0).getValues().get(0);
        assertThat(item.getSourceFileName()).isNull();
        assertThat(item.getSourceFileId()).isEqualTo(FILE_B);
        assertThat(item.getRawValue()).isEqualTo("100万");
        assertThat(item.getNormalizedValue()).isEqualTo("1000000");
        assertThat(item.getSourcePage()).isEqualTo(3);
        assertThat(item.getSourceChunkId()).isEqualTo("chunk-" + FILE_B);
    }

    // ==================== 守门与参数防御 ====================

    /**
     * projectId null：快速失败，不触碰任何依赖（约束 1：查询集中在 Service 且先守门）
     */
    @Test
    void queryProjectFacts_nullProjectId_throwsNPE() {
        assertThatThrownBy(() -> queryService.queryProjectFacts(null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(projectService, projectFormMapper, fieldValueMapper, fileRecordMapper);
    }

    /**
     * 守门唯一入口复用 ProjectService（403）：本服务不重复实现访问控制
     */
    @Test
    void queryProjectFacts_accessDenied_propagates403() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(
                new BusinessException(ResultCode.FORBIDDEN));

        assertThatThrownBy(() -> queryService.queryProjectFacts(PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verifyNoInteractions(projectFormMapper, fieldValueMapper, fileRecordMapper);
    }

    /**
     * 守门唯一入口复用 ProjectService（6001）
     */
    @Test
    void queryProjectFacts_projectMissing_propagates6001() {
        when(projectService.getProjectById(PROJECT_ID)).thenThrow(
                new BusinessException(ResultCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> queryService.queryProjectFacts(PROJECT_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.PROJECT_NOT_FOUND.getCode()));
        verifyNoInteractions(projectFormMapper, fieldValueMapper, fileRecordMapper);
    }

    // ==================== 测试辅助 ====================

    /**
     * 单 ACTIVE 实例 + 项目 VO 的公共打桩（严格模式：仅被当前用例用到才调用）
     */
    private void activeFormStubs() {
        when(projectService.getProjectById(PROJECT_ID)).thenReturn(projectVO());
        when(projectFormMapper.selectList(any())).thenReturn(List.of(form(PROJECT_FORM_ID)));
    }

    private ProjectVO projectVO() {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(PROJECT_ID);
        vo.setProjectName("测试项目");
        return vo;
    }

    private ProjectForm form(Long projectFormId) {
        ProjectForm form = new ProjectForm();
        form.setId(projectFormId);
        form.setProjectId(PROJECT_ID);
        form.setFormId(FORM_ID);
        form.setVersion(1);
        form.setStatus(ProjectFormStatus.ACTIVE);
        return form;
    }

    private ProjectFormFieldValue value(Long projectFormId, String fieldCode,
                                        String rawValue, String normalizedValue, Long fileId) {
        ProjectFormFieldValue value = new ProjectFormFieldValue();
        value.setProjectFormId(projectFormId);
        value.setFieldId(1785400001L);
        value.setFieldCode(fieldCode);
        value.setFieldName("总投资金额");
        value.setFieldType(FieldType.DECIMAL);
        value.setRawValue(rawValue);
        value.setNormalizedValue(normalizedValue);
        value.setUnit("万元");
        value.setSourceFileId(fileId);
        value.setSourcePage(3);
        value.setSourceChunkId("chunk-" + fileId);
        value.setConfidence(new BigDecimal("0.95"));
        return value;
    }

    private FileRecord fileRecord(Long id, String fileName) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setFileName(fileName);
        return record;
    }
}
