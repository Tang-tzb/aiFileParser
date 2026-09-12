package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.assistant.ComparisonSlotExtractor;
import com.aifp.aiagent.assistant.ProjectComparisonService;
import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.dto.ProjectFieldDictionaryVO;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import com.aifp.aiagent.dto.ProjectVO;
import com.aifp.aiagent.entity.enums.FieldType;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.service.FieldComparisonCalculator;
import com.aifp.aiagent.service.ProjectAccessService;
import com.aifp.aiagent.service.ProjectQueryService;
import com.aifp.aiagent.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ProjectComparisonServiceImpl} 编排测试（离线，Phase 10）
 * <p>
 * 覆盖追加约束：1（"当前项目"语义并入、点名列表不含当前项目时不强行加入）、
 * 2（空 projects 不退化全量，范围未定确定性降级）、3（fieldType 校验：非数值字段
 * + 数字诉求 → 不支持计算）、9（候选范围恒为后端集合 + canAccess 过滤，LLM 永不
 * 产出 projectId）、10（名称精确→contains 匹配，多命中确定性降级不猜项目）、
 * 12（无数据/全冲突等"没有足够依据"降级）、14（每次比较重新查询，历史不参与）。
 * FieldComparisonCalculator 使用真实实例验证端到端计算链路。
 *
 * @author Tang_tzb
 */
@ExtendWith(MockitoExtension.class)
class ProjectComparisonServiceImplTest {

    private static final Long CURRENT = 1785900001L;
    private static final Long PROJECT_B = 1785900002L;
    private static final Long PROJECT_C = 1785900003L;
    private static final Long PROJECT_D = 1785900004L;
    private static final Long FORM_CURRENT = 1785600001L;
    private static final Long FORM_B = 1785600002L;
    private static final Long FORM_C = 1785600003L;
    private static final String QUESTION = "当前项目和项目B的总投资比较，谁更高？";
    private static final String FIELD_CODE = "total_investment";

    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private ProjectQueryService projectQueryService;
    @Mock
    private ComparisonSlotExtractor slotExtractor;

    private ProjectComparisonServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProjectComparisonServiceImpl(projectService, projectAccessService,
                projectQueryService, slotExtractor, new FieldComparisonCalculator());
    }

    // ==================== 范围解析（追加约束 1/2/9/10） ====================

    /**
     * 全量语义（追加约束 2 明确全量才允许）：targets = 全部可访问项目集合，
     * 无权限项目（项目D）被 canAccess 过滤且不进入名称清单
     */
    @Test
    void compare_allProjectsScope_usesAccessibleIdsOnly() {
        stubAccessible(CURRENT, PROJECT_B, PROJECT_C, PROJECT_D);
        stubAccessibleFalse(PROJECT_D);
        stubNames(CURRENT, PROJECT_B, PROJECT_C);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), eq(List.of("示范项目", "项目B", "项目C"))))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of(), true, false, true));
        when(projectQueryService.queryFieldFacts(List.of(CURRENT, PROJECT_B, PROJECT_C), FIELD_CODE))
                .thenReturn(facts());

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.deterministicAnswer()).isNull();
        assertThat(outcome.comparison().getUnits()).hasSize(2);
        verify(projectQueryService).queryFieldFacts(List.of(CURRENT, PROJECT_B, PROJECT_C), FIELD_CODE);
    }

    /**
     * 追加约束 1：用户点名列表未含当前项目且无"当前项目"语义 → 严格按点名集合比较，
     * 禁止后端强行加入 currentProjectId
     */
    @Test
    void compare_namedProjectsOnly_currentNotForcedIn() {
        stubAccessible(CURRENT, PROJECT_B, PROJECT_C);
        stubNames(CURRENT, PROJECT_B, PROJECT_C);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("项目B"), false, false, false));
        when(projectQueryService.queryFieldFacts(List.of(PROJECT_B), FIELD_CODE))
                .thenReturn(facts(PROJECT_B, FORM_B, "项目B"));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.deterministicAnswer()).isNull();
        verify(projectQueryService).queryFieldFacts(List.of(PROJECT_B), FIELD_CODE);
        assertThat(outcome.comparison().getCurrentProjectId()).isEqualTo(CURRENT);
    }

    /**
     * 追加约束 1：含"当前项目/本项目"语义（referencesCurrent=true）→ currentProjectId
     * 必须并入目标集合（槽位 Prompt 只给名称，并入由后端完成）
     */
    @Test
    void compare_referencesCurrent_currentMergedIntoTargets() {
        stubAccessible(CURRENT, PROJECT_B, PROJECT_C);
        stubNames(CURRENT, PROJECT_B, PROJECT_C);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("项目B"), false, true, true));
        when(projectQueryService.queryFieldFacts(List.of(CURRENT, PROJECT_B), FIELD_CODE))
                .thenReturn(facts());

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.deterministicAnswer()).isNull();
        verify(projectQueryService).queryFieldFacts(List.of(CURRENT, PROJECT_B), FIELD_CODE);
    }

    /**
     * 追加约束 10：contains 匹配命中多个项目 → "无法唯一确定比较范围"确定性降级，
     * 禁止任选一个；含"当前项目"语义也不补救
     */
    @Test
    void compare_containsMultiHit_fallsBackAmbiguous() {
        stubAccessible(CURRENT, PROJECT_B, PROJECT_C);
        stubNames(CURRENT, "示范项目", PROJECT_B, "项目B分部", PROJECT_C, "项目C分部");
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("分部"), false, true, true));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer())
                .contains("无法唯一确定比较范围").contains("分部");
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 点名项目不在可访问集合（未识别）→ 确定性降级，不猜项目
     */
    @Test
    void compare_unknownName_fallsBack() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("不存在项目"), false, false, false));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer()).contains("未能识别项目").contains("不存在项目");
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 追加约束 2：未点名且无全量语义、无当前项目语义 → 范围未定确定性降级，
     * 禁止退化为全部可访问项目
     */
    @Test
    void compare_emptyScopeWithoutFullSemantic_fallsBack() {
        stubAccessible(CURRENT, PROJECT_B, PROJECT_C);
        stubNames(CURRENT, PROJECT_B, PROJECT_C);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of(), false, false, false));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer()).contains("无法确定比较范围");
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 追加约束 9：候选范围恒来自 listAllProjectIds + canAccess；集合缺失当前项目时
     * 兜底并入（守门已通过的当前项目必须可比较）
     */
    @Test
    void compare_currentMissingFromBackendSet_mergedIn() {
        when(projectService.listAllProjectIds()).thenReturn(List.of(PROJECT_B, PROJECT_C));
        when(projectAccessService.canAccess(PROJECT_B)).thenReturn(true);
        when(projectAccessService.canAccess(PROJECT_C)).thenReturn(true);
        when(projectService.getProjectById(CURRENT)).thenReturn(projectVO(CURRENT, "示范项目"));
        when(projectService.getProjectById(PROJECT_B)).thenReturn(projectVO(PROJECT_B, "项目B"));
        when(projectService.getProjectById(PROJECT_C)).thenReturn(projectVO(PROJECT_C, "项目C"));
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("项目B"), false, false, false));
        when(projectQueryService.queryFieldFacts(List.of(PROJECT_B), FIELD_CODE))
                .thenReturn(facts(PROJECT_B, FORM_B, "项目B"));

        service.compare(CURRENT, QUESTION);

        // 字典查询范围 = 可访问集合 ∪ 当前项目（兜底并入，id 升序）
        verify(projectQueryService).queryFieldDictionary(List.of(CURRENT, PROJECT_B, PROJECT_C));
    }

    // ==================== 字段校验与降级（追加约束 3/12） ====================

    /**
     * fieldCode 空串（抽取失败/不在字典）→ "未能确定要比较的字段"确定性降级，
     * 零事实查询
     */
    @Test
    void compare_fieldUnresolved_fallsBackWithoutFactQuery() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        "", List.of(), false, false, false));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer()).contains("未能确定要比较的字段");
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 追加约束 3：字典无任何字段（可访问项目均无结构化数据）→ 确定性降级
     */
    @Test
    void compare_emptyDictionary_fallsBack() {
        stubAccessible(CURRENT, PROJECT_B);
        // 编排层字典判空先于名称解析短路：本用例零 getProjectById 交互
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(List.of());

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer()).contains("暂无可比较的字段数据");
        verifyNoInteractions(slotExtractor);
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 追加约束 3：字段存在但类型非 INTEGER/DECIMAL 且用户要求数字操作 →
     * "当前不支持排名、求和、平均等数值计算"确定性降级
     */
    @Test
    void compare_nonNumericFieldWithNumericOperation_fallsBack() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(List.of(new ProjectFieldDictionaryVO(
                        "project_type", "项目类型", FieldType.STRING.getCode())));
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        "project_type", List.of("项目B"), false, false, true));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer())
                .contains("项目类型").contains("不是数值型字段").contains("不支持排名、求和、平均");
        verify(projectQueryService, never()).queryFieldFacts(anyList(), anyString());
    }

    /**
     * 追加约束 12：所有目标项目均未提供该字段 → "没有找到足够依据"降级
     */
    @Test
    void compare_allTargetsMissingField_fallsBackNoBasis() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("项目B"), false, true, true));
        when(projectQueryService.queryFieldFacts(List.of(CURRENT, PROJECT_B), FIELD_CODE))
                .thenReturn(List.of(
                        emptyFact(CURRENT, FORM_CURRENT, "示范项目"),
                        emptyFact(PROJECT_B, FORM_B, "项目B")));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer()).contains("没有找到足够依据").contains("总投资金额");
    }

    /**
     * 追加约束 5/12：全部单元冲突 → 计算器参与单元为空 → "没有足够依据"降级
     * （冲突明细保留在 facts 侧，不静默丢失——由编排层话术明确说明）
     */
    @Test
    void compare_allUnitsExcluded_fallsBackNoBasis() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenReturn(new ComparisonSlotExtractor.ComparisonSlots(
                        FIELD_CODE, List.of("项目B"), false, true, true));
        ProjectFieldFactVO conflictCurrent =
                fact(CURRENT, FORM_CURRENT, "示范项目", true, valueItem("100万", "1000000"));
        ProjectFieldFactVO conflictB =
                fact(PROJECT_B, FORM_B, "项目B", true, valueItem("200万", "2000000"));
        when(projectQueryService.queryFieldFacts(List.of(CURRENT, PROJECT_B), FIELD_CODE))
                .thenReturn(List.of(conflictCurrent, conflictB));

        ProjectComparisonService.ComparisonOutcome outcome = service.compare(CURRENT, QUESTION);

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.deterministicAnswer())
                .contains("没有找到足够依据").contains("冲突、无法解析或单位不兼容");
    }

    /**
     * 槽位抽取 ChatModel 异常 → 3001 上抛（Phase 8 追加约束 11 延续）
     */
    @Test
    void compare_slotExtractionFailure_propagates3001() {
        stubAccessible(CURRENT, PROJECT_B);
        stubNames(CURRENT, PROJECT_B);
        when(projectQueryService.queryFieldDictionary(List.of(CURRENT, PROJECT_B)))
                .thenReturn(dictionary());
        when(slotExtractor.extract(eq(QUESTION), anyList(), anyList()))
                .thenThrow(new BusinessException(ResultCode.AI_INVOKE_ERROR, "AI 槽位抽取调用失败"));

        assertThatThrownBy(() -> service.compare(CURRENT, QUESTION))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getCode()).isEqualTo(ResultCode.AI_INVOKE_ERROR.getCode()));
    }

    /**
     * 参数防御：currentProjectId null → NPE；standaloneQuestion 空白 → IAE；
     * 零依赖交互
     */
    @Test
    void compare_illegalArguments_throwsFast() {
        assertThatThrownBy(() -> service.compare(null, QUESTION))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.compare(CURRENT, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(projectService, projectAccessService,
                projectQueryService, slotExtractor);
    }

    // ==================== 测试辅助 ====================

    /**
     * 全部可访问公共打桩：listAllProjectIds + canAccess=true + 逐项目名称守门
     */
    private void stubAccessible(Long... ids) {
        when(projectService.listAllProjectIds()).thenReturn(List.of(ids));
        for (Long id : ids) {
            when(projectAccessService.canAccess(id)).thenReturn(true);
        }
    }

    private void stubAccessibleFalse(Long id) {
        when(projectAccessService.canAccess(id)).thenReturn(false);
    }

    /**
     * 名称打桩（默认名，id 升序语义）：仅对传入 id 打桩——编排层按可访问集合逐项目
     * 守门取名，严格 Mockito 下与用例集合不一致的多余 stub 会直接失败
     */
    private void stubNames(Long... ids) {
        for (Long id : ids) {
            when(projectService.getProjectById(id)).thenReturn(projectVO(id, defaultName(id)));
        }
    }

    private String defaultName(Long id) {
        if (CURRENT.equals(id)) {
            return "示范项目";
        }
        if (PROJECT_B.equals(id)) {
            return "项目B";
        }
        if (PROJECT_C.equals(id)) {
            return "项目C";
        }
        throw new IllegalArgumentException("未定义默认项目名: " + id);
    }

    private void stubNames(Long id1, String name1, Long id2, String name2, Long id3, String name3) {
        when(projectService.getProjectById(id1)).thenReturn(projectVO(id1, name1));
        when(projectService.getProjectById(id2)).thenReturn(projectVO(id2, name2));
        when(projectService.getProjectById(id3)).thenReturn(projectVO(id3, name3));
    }

    private ProjectVO projectVO(Long id, String name) {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(id);
        vo.setProjectName(name);
        return vo;
    }

    private List<ProjectFieldDictionaryVO> dictionary() {
        return List.of(new ProjectFieldDictionaryVO(
                FIELD_CODE, "总投资金额", FieldType.DECIMAL.getCode()));
    }

    /**
     * 双项目（当前 + 项目B）各一实例的标准事实：1000000 / 2000000 万元，无冲突
     */
    private List<ProjectFieldFactVO> facts() {
        return List.of(
                fact(CURRENT, FORM_CURRENT, "示范项目", false, valueItem("100万", "1000000")),
                fact(PROJECT_B, FORM_B, "项目B", false, valueItem("200万", "2000000")));
    }

    /**
     * 单项目事实（无冲突）
     */
    private List<ProjectFieldFactVO> facts(Long projectId, Long formId, String projectName) {
        return List.of(fact(projectId, formId, projectName, false, valueItem("200万", "2000000")));
    }

    private ProjectFieldFactVO emptyFact(Long projectId, Long formId, String projectName) {
        return fact(projectId, formId, projectName, false);
    }

    private ProjectFieldFactVO fact(Long projectId, Long formId, String projectName,
                                    boolean conflict,
                                    ProjectStructuredFactsVO.ValueItem... values) {
        ProjectFieldFactVO fact = new ProjectFieldFactVO();
        fact.setProjectId(projectId);
        fact.setProjectName(projectName);
        fact.setProjectFormId(formId);
        fact.setFieldCode(FIELD_CODE);
        fact.setConflict(conflict);
        if (values.length > 0) {
            fact.setFieldName("总投资金额");
            fact.setFieldType(FieldType.DECIMAL.getCode());
            fact.getValues().addAll(List.of(values));
        }
        return fact;
    }

    private ProjectStructuredFactsVO.ValueItem valueItem(String raw, String normalized) {
        ProjectStructuredFactsVO.ValueItem item = new ProjectStructuredFactsVO.ValueItem();
        item.setRawValue(raw);
        item.setNormalizedValue(normalized);
        item.setUnit("万元");
        item.setSourceFileId(1785800001L);
        item.setSourceFileName("来源文件.pdf");
        item.setSourcePage(1);
        return item;
    }
}
