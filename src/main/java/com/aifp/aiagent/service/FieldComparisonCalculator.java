package com.aifp.aiagent.service;

import com.aifp.aiagent.dto.CrossProjectComparisonVO;
import com.aifp.aiagent.dto.CrossProjectComparisonVO.Aggregates;
import com.aifp.aiagent.dto.CrossProjectComparisonVO.ExcludedUnit;
import com.aifp.aiagent.dto.CrossProjectComparisonVO.Unit;
import com.aifp.aiagent.dto.ProjectFieldFactVO;
import com.aifp.aiagent.dto.ProjectStructuredFactsVO.ValueItem;
import com.aifp.aiagent.entity.enums.FieldType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 跨项目字段比较计算器（Phase 10 Comparison / Ranking / Aggregation）
 * <p>
 * 纯函数组件（紧邻 {@link FieldValueConflictResolver} 同款定位）：不依赖 Mapper/LLM，
 * 输入字段事实单元列表，输出确定性比较结果。数字结论唯一来源（追加约束 6）：
 * rank/aggregates/diffFromCurrent 全部由本类以 BigDecimal 生成（追加约束 7，
 * 禁止 double/float），LLM 只能引用。
 * <p>
 * 参与资格（追加约束 3/5/8）：fieldType ∈ {INTEGER, DECIMAL} 且 normalizedValue
 * 可解析且 conflict=false 且 unit 与基准单位一致；不合格单元进入 {@code excluded}
 * （原因：CONFLICT / UNPARSEABLE / UNIT_INCOMPATIBLE / NO_DATA），禁止静默丢弃。
 * <p>
 * 实例独立（追加约束 4）：每个事实单元 (projectId, projectFormId, fieldCode)
 * 独立参与，跨项目 fieldCode 相同不合并。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class FieldComparisonCalculator {

    /**
     * 平均值固定精度：scale=4、HALF_UP（追加约束 7）
     */
    private static final int AVG_SCALE = 4;

    /**
     * 差值/百分比固定精度：scale=4、HALF_UP（与 avg 对齐，追加约束 7）
     */
    private static final int DIFF_SCALE = 4;

    /**
     * 数值可计算字段判定（追加约束 3：仅 INTEGER/DECIMAL；编排层校验复用）
     */
    public static boolean isNumericField(String fieldType) {
        return FieldType.INTEGER.getCode().equals(fieldType)
                || FieldType.DECIMAL.getCode().equals(fieldType);
    }

    // ==================== 内部方法 ====================

    /**
     * 计算跨项目比较结果（不修改输入）。
     * <p>
     * 数值字段：units 按 normalizedValue 降序（同值并列 1,2,2,4 竞赛排名）；
     * 非数值字段（DATE/STRING/BOOLEAN）：units 按 (projectId, projectFormId) 升序
     * 仅列值，无 rank、无 aggregates（追加约束 3：不得数值计算）。
     * 参与单元为空时 aggregates 为 null（调用方负责"没有足够依据"判定）。
     *
     * @param facts            字段事实单元（来自 ProjectQueryService.queryFieldFacts，form id 升序）
     * @param currentProjectId 当前项目 ID
     * @param fieldCode        字段编码
     * @return 比较结果 VO（fieldName/fieldType 可能为 null：全部实例无该字段定义快照）
     */
    public CrossProjectComparisonVO compute(List<ProjectFieldFactVO> facts,
                                            Long currentProjectId, String fieldCode) {
        Objects.requireNonNull(facts, "facts 不可为空");
        Objects.requireNonNull(currentProjectId, "currentProjectId 不可为空");
        CrossProjectComparisonVO result = new CrossProjectComparisonVO();
        result.setFieldCode(fieldCode);
        result.setCurrentProjectId(currentProjectId);
        fillFieldMeta(result, facts);

        boolean numeric = isNumericField(result.getFieldType());
        List<ProjectFieldFactVO> candidates = facts.stream()
                .filter(fact -> !fact.getValues().isEmpty())
                .toList();
        facts.stream().filter(fact -> fact.getValues().isEmpty())
                .forEach(fact -> result.getExcluded().add(toExcluded(fact, "NO_DATA")));

        if (!numeric) {
            // 非数值字段：仅列值，不排名不聚合（追加约束 3）；
            // 冲突单元同样进 excluded（追加约束 5：禁止静默丢弃）
            result.setAggregates(null);
            candidates.stream().filter(ProjectFieldFactVO::isConflict)
                    .forEach(fact -> result.getExcluded().add(toExcluded(fact, "CONFLICT")));
            candidates.stream().filter(fact -> !fact.isConflict())
                    .forEach(fact -> result.getUnits().add(toUnit(fact, representativeRow(fact), null)));
            result.getUnits().sort(Comparator.comparing(Unit::getProjectId)
                    .thenComparing(Unit::getProjectFormId));
            return result;
        }
        computeNumeric(result, candidates, currentProjectId);
        return result;
    }

    /**
     * 数值字段计算主流程：资格过滤（冲突/不可解析/单位不兼容）→ 排名 → 聚合 → 差值。
     */
    private void computeNumeric(CrossProjectComparisonVO result,
                                List<ProjectFieldFactVO> candidates, Long currentProjectId) {
        List<ValueItem> participatingRows = new ArrayList<>();
        List<ProjectFieldFactVO> participatingFacts = new ArrayList<>();
        // 基准单位 = 第一个候选单元（form id 升序）代表行的 unit（追加约束 8）
        String baseUnit = null;
        for (ProjectFieldFactVO fact : candidates) {
            if (fact.isConflict()) {
                result.getExcluded().add(toExcluded(fact, "CONFLICT"));
                continue;
            }
            ValueItem row = representativeRow(fact);
            BigDecimal value = parseNormalized(row.getNormalizedValue());
            if (value == null) {
                result.getExcluded().add(toExcluded(fact, "UNPARSEABLE"));
                continue;
            }
            if (baseUnit == null) {
                baseUnit = row.getUnit();
            }
            if (!Objects.equals(baseUnit, row.getUnit())) {
                // 单位不一致且当前版本无转换规则：不参与计算，禁止 LLM 猜单位关系（追加约束 8）
                result.getExcluded().add(toExcluded(fact, "UNIT_INCOMPATIBLE"));
                continue;
            }
            participatingFacts.add(fact);
            participatingRows.add(row);
        }
        result.setUnit(baseUnit);
        if (participatingFacts.isEmpty()) {
            result.setAggregates(null);
            return;
        }
        List<BigDecimal> values = participatingRows.stream()
                .map(row -> parseNormalized(row.getNormalizedValue()))
                .toList();
        assignUnitsWithRank(result, participatingFacts, participatingRows, values);
        result.setAggregates(buildAggregates(values));
        applyDiffToCurrent(result, participatingFacts, participatingRows, values, currentProjectId);
    }

    /**
     * 组装参与单元并按 normalizedValue 降序排列 + 竞赛排名（同值并列，1,2,2,4）。
     */
    private void assignUnitsWithRank(CrossProjectComparisonVO result,
                                     List<ProjectFieldFactVO> facts, List<ValueItem> rows,
                                     List<BigDecimal> values) {
        List<Unit> units = new ArrayList<>(facts.size());
        for (int i = 0; i < facts.size(); i++) {
            units.add(toUnit(facts.get(i), rows.get(i), rankOf(values, i)));
        }
        units.sort(Comparator.comparing(
                (Unit unit) -> new BigDecimal(unit.getNormalizedValue())).reversed());
        result.getUnits().addAll(units);
    }

    /**
     * 竞赛排名：严格大于的个数 + 1（同值并列同名次，下一个名次跳位，1,2,2,4）。
     */
    private Integer rankOf(List<BigDecimal> values, int index) {
        BigDecimal own = values.get(index);
        int greater = 0;
        for (BigDecimal value : values) {
            if (value.compareTo(own) > 0) {
                greater++;
            }
        }
        return greater + 1;
    }

    /**
     * 聚合结果：max/min/sum/avg/count 仅在参与单元上计算；
     * avg 固定 HALF_UP + scale=4（追加约束 7）。
     */
    private Aggregates buildAggregates(List<BigDecimal> values) {
        Aggregates aggregates = new Aggregates();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        aggregates.setMax(values.stream().reduce(BigDecimal::max).orElse(null));
        aggregates.setMin(values.stream().reduce(BigDecimal::min).orElse(null));
        aggregates.setSum(sum);
        aggregates.setAvg(sum.divide(BigDecimal.valueOf(values.size()), AVG_SCALE, RoundingMode.HALF_UP));
        aggregates.setCount(values.size());
        return aggregates;
    }

    /**
     * 差值/差值百分比：仅当当前项目恰有 1 个参与单元时对该单元外逐单元计算
     * （unit − current；percent = diff / |current|，scale=4 HALF_UP，current=0 → null），
     * 否则全部为 null——禁止 LLM 自算（追加约束 6）。
     */
    private void applyDiffToCurrent(CrossProjectComparisonVO result, List<ProjectFieldFactVO> facts,
                                    List<ValueItem> rows, List<BigDecimal> values, Long currentProjectId) {
        List<Integer> currentIndexes = currentIndexes(facts, currentProjectId);
        if (currentIndexes.size() != 1) {
            return;
        }
        int currentIdx = currentIndexes.get(0);
        BigDecimal current = values.get(currentIdx);
        for (int i = 0; i < facts.size(); i++) {
            if (i == currentIdx) {
                continue;
            }
            BigDecimal diff = values.get(i).subtract(current)
                    .setScale(DIFF_SCALE, RoundingMode.HALF_UP);
            result.getUnits().get(indexOfUnit(result, facts.get(i))).setDiffFromCurrent(diff);
            if (current.compareTo(BigDecimal.ZERO) != 0) {
                result.getUnits().get(indexOfUnit(result, facts.get(i)))
                        .setDiffFromCurrentPercent(diff.divide(current.abs(), DIFF_SCALE, RoundingMode.HALF_UP));
            }
        }
    }

    /**
     * 当前项目参与单元的下标列表（0 个或多个时差值均不计算）
     */
    private List<Integer> currentIndexes(List<ProjectFieldFactVO> facts, Long currentProjectId) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            if (currentProjectId.equals(facts.get(i).getProjectId())) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    /**
     * 事实单元在结果 units 中的下标（按 projectFormId 定位）
     */
    private int indexOfUnit(CrossProjectComparisonVO result, ProjectFieldFactVO fact) {
        for (int i = 0; i < result.getUnits().size(); i++) {
            if (result.getUnits().get(i).getProjectFormId().equals(fact.getProjectFormId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 字段元数据填充：fieldName/fieldType 取第一个有定义快照的单元（form id 升序）
     */
    private void fillFieldMeta(CrossProjectComparisonVO result, List<ProjectFieldFactVO> facts) {
        result.setTargetProjectIds(facts.stream().map(ProjectFieldFactVO::getProjectId).distinct().toList());
        facts.stream().filter(fact -> fact.getFieldType() != null).findFirst()
                .ifPresent(fact -> {
                    result.setFieldName(fact.getFieldName());
                    result.setFieldType(fact.getFieldType());
                });
    }

    /**
     * 代表行：非冲突单元内多行 normalizedValue 相同，取 id 升序第一行作 rawValue 展示行
     */
    private ValueItem representativeRow(ProjectFieldFactVO fact) {
        return fact.getValues().get(0);
    }

    /**
     * normalizedValue → BigDecimal（不可解析返回 null，调用方进入 excluded）
     */
    private BigDecimal parseNormalized(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalizedValue.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 事实单元 → 参与单元投影（rank 可空：非数值字段/调用方未排名）
     */
    private Unit toUnit(ProjectFieldFactVO fact, ValueItem row, Integer rank) {
        Unit unit = new Unit();
        unit.setProjectId(fact.getProjectId());
        unit.setProjectName(fact.getProjectName());
        unit.setProjectFormId(fact.getProjectFormId());
        unit.setRawValue(row.getRawValue());
        unit.setNormalizedValue(row.getNormalizedValue());
        unit.setUnit(row.getUnit());
        unit.setRank(rank);
        unit.setSourceFileId(row.getSourceFileId());
        unit.setSourceFileName(row.getSourceFileName());
        unit.setSourcePage(row.getSourcePage());
        unit.setSourceChunkId(row.getSourceChunkId());
        return unit;
    }

    /**
     * 事实单元 → 排除单元投影（全部来源值随行保留，追加约束 5）
     */
    private ExcludedUnit toExcluded(ProjectFieldFactVO fact, String reason) {
        ExcludedUnit excluded = new ExcludedUnit();
        excluded.setReason(reason);
        excluded.setProjectId(fact.getProjectId());
        excluded.setProjectName(fact.getProjectName());
        excluded.setProjectFormId(fact.getProjectFormId());
        excluded.setFieldCode(fact.getFieldCode());
        excluded.setValues(List.copyOf(fact.getValues()));
        return excluded;
    }
}
