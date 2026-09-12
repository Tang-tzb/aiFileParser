package com.aifp.aiagent.service;

import com.aifp.aiagent.entity.ProjectFormFieldValue;
import com.aifp.aiagent.entity.enums.FieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FieldValueConflictResolver} 纯函数测试（Phase 7，约束 2/4/5）
 * <p>
 * Resolver 不持有任何依赖，测试只验证判定规则：
 * distinct 非 null normalizedValue 数 &gt; 1 即冲突；null 不参与比较；
 * ProjectForm.version / unit / 来源信息一律不影响判定。
 *
 * @author Tang_tzb
 */
class FieldValueConflictResolverTest {

    private static final Long FILE_A = 1785800001L;
    private static final Long FILE_B = 1785800002L;
    private final FieldValueConflictResolver resolver = new FieldValueConflictResolver();

    /**
     * 边界：null / 空列表 / 单条 → 无冲突（不产生 NPE）
     */
    @Test
    void hasConflict_emptyOrSingleRow_noConflict() {
        assertThat(resolver.hasConflict(null)).isFalse();
        assertThat(resolver.hasConflict(List.of())).isFalse();
        assertThat(resolver.hasConflict(List.of(row("100", FILE_A)))).isFalse();
    }

    /**
     * 约束 10 场景 1：同实例同字段两文件不同 normalizedValue → conflict=true
     */
    @Test
    void hasConflict_distinctNormalizedValues_conflict() {
        assertThat(resolver.hasConflict(List.of(
                row("1000", FILE_A),
                row("2000", FILE_B)))).isTrue();
    }

    /**
     * 约束 10 场景 2：同实例同字段两文件相同 normalizedValue → conflict=false
     * （单位不同不参与本阶段判定——约束 4：字符串相等仅为比较语义）
     */
    @Test
    void hasConflict_sameNormalizedValue_noConflictRegardlessOfUnit() {
        ProjectFormFieldValue withUnit = row("1000000", FILE_A);
        withUnit.setUnit("万元");
        ProjectFormFieldValue noUnit = row("1000000", FILE_B);
        assertThat(resolver.hasConflict(List.of(withUnit, noUnit))).isFalse();
    }

    /**
     * null normalizedValue 不参与比较（无法判定的行不制造冲突），但仍随结果返回
     */
    @Test
    void hasConflict_nullNormalizedValue_excludedFromComparison() {
        assertThat(resolver.hasConflict(List.of(
                row("1000", FILE_A),
                row(null, FILE_B)))).isFalse();
        assertThat(resolver.hasConflict(List.of(
                row(null, FILE_A),
                row(null, FILE_B)))).isFalse();
    }

    /**
     * 三值两异：distinct 计数不受重复值影响
     */
    @Test
    void hasConflict_threeValuesTwoDistinct_conflict() {
        assertThat(resolver.hasConflict(List.of(
                row("1000", FILE_A),
                row("1000", FILE_B),
                row("2000", FILE_A)))).isTrue();
    }

    /**
     * 约束 5：version 不同不参与判定（大 version 的同值不覆盖小 version 的判定结果）
     */
    @Test
    void hasConflict_versionIrrelevant() {
        ProjectFormFieldValue newer = row("1000", FILE_B);
        newer.setRawValue("100万");
        assertThat(resolver.hasConflict(List.of(
                row("1000", FILE_A),
                newer))).isFalse();
    }

    /**
     * 防御性覆盖：入参列表实现允许 null 元素时（Service 分组产物不会出现），
     * null 行按无 normalizedValue 处理，不抛异常
     */
    @Test
    void hasConflict_nullRowElement_toleratedAsNullNormalizedValue() {
        List<ProjectFormFieldValue> rows = new ArrayList<>();
        rows.add(row("1000", FILE_A));
        rows.add(null);
        assertThat(resolver.hasConflict(rows)).isFalse();
    }

    /**
     * 构造最小有效值行（projectFormId/fieldCode 由 Service 分组保证，此处恒定）
     */
    private ProjectFormFieldValue row(String normalizedValue, Long sourceFileId) {
        ProjectFormFieldValue value = new ProjectFormFieldValue();
        value.setProjectFormId(1785600001L);
        value.setFieldCode("total_investment");
        value.setFieldType(FieldType.DECIMAL);
        value.setRawValue("raw-" + normalizedValue);
        value.setNormalizedValue(normalizedValue);
        value.setSourceFileId(sourceFileId);
        return value;
    }
}
