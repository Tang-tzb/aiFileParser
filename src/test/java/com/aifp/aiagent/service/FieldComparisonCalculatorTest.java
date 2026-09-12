package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO;
import com.aifp.aiagent.entity.enums.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FieldComparisonCalculator} 测试（离线纯函数，Phase 10）
 * <p>
 * 覆盖追加约束：3（非数值字段不做数值计算）、4（事实单元实例独立）、
 * 5（冲突排除可解释且不静默丢弃）、6（数字结论唯一来源）、7（BigDecimal 统一规则
 * avg HALF_UP scale=4）、8（单位参与计算前验证）；以及排名并列（1,2,2,4 竞赛排名）、
 * 聚合、差值/百分比、全部排除时的空结果语义（追加约束 12）。
 *
 * @author Tang_tzb
 */
class FieldComparisonCalculatorTest {

    private static final Long PROJECT_A = 1785900001L;
    private static final Long PROJECT_B = 1785900002L;
    private static final Long PROJECT_C = 1785900003L;
    private static final String FIELD_CODE = "total_investment";

    private final FieldComparisonCalculator calculator = new FieldComparisonCalculator();

    // ==================== 数值字段：排名与聚合（追加约束 6/7） ====================

    /**
     * 排名按 normalizedValue 降序 + 竞赛排名（同值并列同名次，1,2,2）；
     * 聚合 max/min/sum/avg/count 全部后端生成，avg 固定 scale=4（追加约束 7）
     */
    @Test
    void numeric_rankingDescendingWithTiesAndAggregates() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L, valueItem("200万", "2000000", "万元")),
                fact(PROJECT_C, "项目C", 3L, valueItem("100万", "1000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        // units 按 normalizedValue 降序：B(2000000)、A(1000000)、C(1000000)
        assertThat(result.getUnits()).extracting(CrossProjectComparisonVO.Unit::getProjectId)
                .containsExactly(PROJECT_B, PROJECT_A, PROJECT_C);
        assertThat(result.getUnits()).extracting(CrossProjectComparisonVO.Unit::getRank)
                .containsExactly(1, 2, 2);
        assertThat(result.getUnit()).isEqualTo("万元");
        CrossProjectComparisonVO.Aggregates aggregates = result.getAggregates();
        assertThat(aggregates.getMax()).isEqualByComparingTo("2000000");
        assertThat(aggregates.getMin()).isEqualByComparingTo("1000000");
        assertThat(aggregates.getSum()).isEqualByComparingTo("4000000");
        // avg = 4000000/3 = 1333333.3333（HALF_UP scale=4）
        assertThat(aggregates.getAvg()).isEqualByComparingTo("1333333.3333");
        assertThat(aggregates.getAvg().scale()).isEqualTo(4);
        assertThat(aggregates.getCount()).isEqualTo(3);
        assertThat(result.getExcluded()).isEmpty();
    }

    /**
     * 差值/百分比仅对当前项目恰 1 个参与单元时计算：diff = unit − current、
     * percent = diff / |current|（scale=4 HALF_UP，追加约束 7）
     */
    @Test
    void numeric_diffFromCurrentComputedForSingleCurrentUnit() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L, valueItem("200万", "2000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        CrossProjectComparisonVO.Unit currentUnit = unitOf(result, 1L);
        CrossProjectComparisonVO.Unit targetUnit = unitOf(result, 2L);
        assertThat(currentUnit.getDiffFromCurrent()).isNull();
        assertThat(targetUnit.getDiffFromCurrent()).isEqualByComparingTo("1000000");
        assertThat(targetUnit.getDiffFromCurrentPercent()).isEqualByComparingTo("1.0000");
    }

    /**
     * 当前项目存在 2 个参与单元（同项目双表单实例，追加约束 4：实例独立）→
     * 差值语义不唯一，全部不计算（禁止猜测基准）
     */
    @Test
    void numeric_currentProjectTwoUnits_diffsNotComputed() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_A, "项目A", 2L, valueItem("150万", "1500000", "万元")),
                fact(PROJECT_B, "项目B", 3L, valueItem("200万", "2000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnits()).hasSize(3);
        assertThat(result.getUnits()).allSatisfy(unit -> {
            assertThat(unit.getDiffFromCurrent()).isNull();
            assertThat(unit.getDiffFromCurrentPercent()).isNull();
        });
    }

    /**
     * current=0 时百分比除零保护：diff 照常计算，percent=null
     */
    @Test
    void numeric_currentZero_diffPercentNull() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("0", "0", "万元")),
                fact(PROJECT_B, "项目B", 2L, valueItem("200万", "2000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        CrossProjectComparisonVO.Unit targetUnit = unitOf(result, 2L);
        assertThat(targetUnit.getDiffFromCurrent()).isEqualByComparingTo("2000000");
        assertThat(targetUnit.getDiffFromCurrentPercent()).isNull();
    }

    // ==================== 参与资格与排除（追加约束 3/5/8） ====================

    /**
     * 冲突单元（追加约束 5）：不参与排名/聚合，进入 excluded 且原因=CONFLICT、
     * 全部来源值保留；参与单元的聚合不受影响
     */
    @Test
    void conflictUnit_excludedWithAllSourceValues() {
        ProjectFieldFactVO conflict = fact(PROJECT_B, "项目B", 2L, true,
                valueItem("180万", "1800000", "万元"),
                valueItem("220万", "2200000", "万元"));
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                conflict);

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnits()).hasSize(1);
        assertThat(result.getUnits().get(0).getProjectId()).isEqualTo(PROJECT_A);
        assertThat(result.getAggregates().getCount()).isEqualTo(1);
        assertThat(result.getExcluded()).hasSize(1);
        CrossProjectComparisonVO.ExcludedUnit excluded = result.getExcluded().get(0);
        assertThat(excluded.getReason()).isEqualTo("CONFLICT");
        assertThat(excluded.getProjectId()).isEqualTo(PROJECT_B);
        assertThat(excluded.getProjectFormId()).isEqualTo(2L);
        assertThat(excluded.getFieldCode()).isEqualTo(FIELD_CODE);
        assertThat(excluded.getValues()).hasSize(2)
                .extracting(ProjectStructuredFactsVO.ValueItem::getRawValue)
                .containsExactly("180万", "220万");
    }

    /**
     * normalizedValue 不可解析（追加约束 12）：进入 excluded 原因=UNPARSEABLE
     */
    @Test
    void unparseableValue_excludedWithReason() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("约100万", "N/A", "万元")),
                fact(PROJECT_B, "项目B", 2L, valueItem("200万", "2000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnits()).hasSize(1);
        assertThat(result.getExcluded()).hasSize(1);
        assertThat(result.getExcluded().get(0).getReason()).isEqualTo("UNPARSEABLE");
        assertThat(result.getExcluded().get(0).getProjectId()).isEqualTo(PROJECT_A);
    }

    /**
     * 单位不一致且无转换规则（追加约束 8）：基准单位 = 首个候选单元的 unit，
     * 其余不一致单元进入 excluded 原因=UNIT_INCOMPATIBLE，禁止 LLM 猜单位关系
     */
    @Test
    void incompatibleUnit_excludedAndBaseUnitFromFirstCandidate() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L, valueItem("1亿", "1000000000", "元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnit()).isEqualTo("万元");
        assertThat(result.getUnits()).hasSize(1);
        assertThat(result.getUnits().get(0).getProjectId()).isEqualTo(PROJECT_A);
        assertThat(result.getExcluded()).hasSize(1);
        assertThat(result.getExcluded().get(0).getReason()).isEqualTo("UNIT_INCOMPATIBLE");
        assertThat(result.getExcluded().get(0).getProjectId()).isEqualTo(PROJECT_B);
    }

    /**
     * 无值单元（追加约束 12）：进入 excluded 原因=NO_DATA（供"项目X 未提供数据"解释）
     */
    @Test
    void noDataUnit_excludedWithReason() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnits()).hasSize(1);
        assertThat(result.getExcluded()).hasSize(1);
        assertThat(result.getExcluded().get(0).getReason()).isEqualTo("NO_DATA");
    }

    /**
     * 全部排除（追加约束 12）：参与单元为空 → units 空且 aggregates=null，
     * 由编排层判定"没有足够依据"
     */
    @Test
    void allExcluded_unitsEmptyAndAggregatesNull() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, true, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L, true, valueItem("200万", "2000000", "万元")));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getUnits()).isEmpty();
        assertThat(result.getAggregates()).isNull();
        assertThat(result.getExcluded()).hasSize(2);
    }

    // ==================== 非数值字段（追加约束 3） ====================

    /**
     * 非数值字段（STRING/DATE/BOOLEAN）：仅列值（按 projectId,projectFormId 升序），
     * 无 rank、无 aggregates；冲突单元同样进 excluded（追加约束 5 不静默丢弃）
     */
    @Test
    void nonNumericField_valuesOnlyWithoutRankOrAggregates() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_B, "项目B", 2L, false, FieldType.STRING.getCode(),
                        valueItem("商业", "商业", null)),
                fact(PROJECT_A, "项目A", 1L, false, FieldType.STRING.getCode(),
                        valueItem("住宅", "住宅", null)),
                fact(PROJECT_C, "项目C", 3L, true, FieldType.STRING.getCode(),
                        valueItem("工业", "工业", null)));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getAggregates()).isNull();
        assertThat(result.getUnits()).extracting(CrossProjectComparisonVO.Unit::getProjectId)
                .containsExactly(PROJECT_A, PROJECT_B);
        assertThat(result.getUnits()).allSatisfy(unit -> assertThat(unit.getRank()).isNull());
        assertThat(result.getExcluded()).hasSize(1);
        assertThat(result.getExcluded().get(0).getReason()).isEqualTo("CONFLICT");
        assertThat(result.getExcluded().get(0).getProjectId()).isEqualTo(PROJECT_C);
    }

    // ==================== 元数据与防御 ====================

    /**
     * fieldName/fieldType 取第一个有定义快照的单元；全部无值时元数据为 null
     * 但 fieldCode/currentProjectId/targetProjectIds 仍完整
     */
    @Test
    void fieldMetaFromFirstDefinedUnit() {
        List<ProjectFieldFactVO> facts = List.of(
                fact(PROJECT_A, "项目A", 1L, valueItem("100万", "1000000", "万元")),
                fact(PROJECT_B, "项目B", 2L));

        CrossProjectComparisonVO result = calculator.compute(facts, PROJECT_A, FIELD_CODE);

        assertThat(result.getFieldCode()).isEqualTo(FIELD_CODE);
        assertThat(result.getFieldName()).isEqualTo("总投资金额");
        assertThat(result.getFieldType()).isEqualTo(FieldType.DECIMAL.getCode());
        assertThat(result.getCurrentProjectId()).isEqualTo(PROJECT_A);
        assertThat(result.getTargetProjectIds()).containsExactly(PROJECT_A, PROJECT_B);

        CrossProjectComparisonVO empty = calculator.compute(
                List.of(fact(PROJECT_B, "项目B", 2L)), PROJECT_A, FIELD_CODE);
        assertThat(empty.getFieldName()).isNull();
        assertThat(empty.getFieldType()).isNull();
    }

    /**
     * 参数防御：facts/currentProjectId null → NPE（编排层契约保证）
     */
    @Test
    void nullArguments_throwsNPE() {
        assertThatThrownBy(() -> calculator.compute(null, PROJECT_A, FIELD_CODE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.compute(List.of(), null, FIELD_CODE))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 数值可计算判定（追加约束 3）：仅 INTEGER/DECIMAL
     */
    @Test
    void isNumericField_onlyIntegerAndDecimal() {
        assertThat(FieldComparisonCalculator.isNumericField(FieldType.INTEGER.getCode())).isTrue();
        assertThat(FieldComparisonCalculator.isNumericField(FieldType.DECIMAL.getCode())).isTrue();
        assertThat(FieldComparisonCalculator.isNumericField(FieldType.STRING.getCode())).isFalse();
        assertThat(FieldComparisonCalculator.isNumericField(FieldType.DATE.getCode())).isFalse();
        assertThat(FieldComparisonCalculator.isNumericField(FieldType.BOOLEAN.getCode())).isFalse();
        assertThat(FieldComparisonCalculator.isNumericField(null)).isFalse();
    }

    // ==================== 测试辅助 ====================

    /**
     * 单实例事实单元（未冲突，fieldName/fieldType 取 DECIMAL 快照）
     */
    private ProjectFieldFactVO fact(Long projectId, String projectName, Long projectFormId,
                                    ProjectStructuredFactsVO.ValueItem... values) {
        return fact(projectId, projectName, projectFormId, false, values);
    }

    private ProjectFieldFactVO fact(Long projectId, String projectName, Long projectFormId,
                                    boolean conflict, ProjectStructuredFactsVO.ValueItem... values) {
        return fact(projectId, projectName, projectFormId, conflict,
                FieldType.DECIMAL.getCode(), values);
    }

    /**
     * 指定 fieldType 快照的事实单元（非数值字段用例使用，追加约束 3）
     */
    private ProjectFieldFactVO fact(Long projectId, String projectName, Long projectFormId,
                                    boolean conflict, String fieldType,
                                    ProjectStructuredFactsVO.ValueItem... values) {
        ProjectFieldFactVO fact = new ProjectFieldFactVO();
        fact.setProjectId(projectId);
        fact.setProjectName(projectName);
        fact.setProjectFormId(projectFormId);
        fact.setFieldCode(FIELD_CODE);
        fact.setConflict(conflict);
        if (values.length > 0) {
            fact.setFieldName("总投资金额");
            fact.setFieldType(fieldType);
            fact.getValues().addAll(List.of(values));
        }
        return fact;
    }

    private ProjectStructuredFactsVO.ValueItem valueItem(String raw, String normalized, String unit) {
        ProjectStructuredFactsVO.ValueItem item = new ProjectStructuredFactsVO.ValueItem();
        item.setRawValue(raw);
        item.setNormalizedValue(normalized);
        item.setUnit(unit);
        item.setSourceFileId(1785800001L);
        item.setSourceFileName("来源文件.pdf");
        item.setSourcePage(3);
        item.setSourceChunkId("chunk-1");
        return item;
    }

    /**
     * 按 projectFormId 取参与单元（测试内目标项目恒单实例）
     */
    private CrossProjectComparisonVO.Unit unitOf(CrossProjectComparisonVO result, Long projectFormId) {
        return result.getUnits().stream()
                .filter(unit -> unit.getProjectFormId().equals(projectFormId))
                .findFirst()
                .orElseThrow();
    }
}
