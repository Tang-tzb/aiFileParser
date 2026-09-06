package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OcrEligibilityEvaluator} 策略矩阵测试（v2 冻结规则）。
 * <p>
 * 核心断言：coverageRatio 只是决策输入之一，禁止"覆盖率低 → 直接 OCR"；
 * TEXT/STAMP/SIGNATURE 恒不 OCR；整页图区域豁免（禁变相整页 OCR）。
 *
 * @author Tang_tzb
 */
class OcrEligibilityEvaluatorTest {

    private final OcrEligibilityEvaluator evaluator = new OcrEligibilityEvaluator();

    /**
     * 构建区域（显式指定全部决策输入，避免默认值干扰断言语义）。
     */
    private VisualRegion region(RegionType type, boolean likelyTable,
                                double coverage, double candidate, double pageAreaRatio) {
        return VisualRegion.builder()
                .regionType(type)
                .bbox(BoundingBox.builder().x(100).y(400).width(200).height(150).build())
                .likelyTable(likelyTable)
                .tableScore(likelyTable ? 0.5 : 0)
                .coverageRatio(coverage)
                .textCandidateScore(candidate)
                .pageAreaRatio(pageAreaRatio)
                .build();
    }

    @Test
    void nullRegionOrBbox_notEligible() {
        assertThat(evaluator.shouldOcr(null)).isFalse();
        assertThat(evaluator.shouldOcr(VisualRegion.builder()
                .regionType(RegionType.TABLE).build())).isFalse();
    }

    @Test
    void textRegion_neverOcr() {
        assertThat(evaluator.shouldOcr(region(RegionType.TEXT, false, 0.9, 0.9, 0.1))).isFalse();
    }

    @Test
    void stampRegion_neverOcr() {
        assertThat(evaluator.shouldOcr(region(RegionType.STAMP, false, 0.0, 0.9, 0.05))).isFalse();
    }

    @Test
    void signatureRegion_neverOcr() {
        assertThat(evaluator.shouldOcr(region(RegionType.SIGNATURE, false, 0.0, 0.9, 0.05))).isFalse();
    }

    @Test
    void tableRegion_alwaysEligible() {
        assertThat(evaluator.shouldOcr(region(RegionType.TABLE, false, 0.9, 0.0, 0.1))).isTrue();
    }

    @Test
    void likelyTableCandidate_eligible_evenForImageType() {
        // likelyTable 优先于类型判定（候选标记 = 阶段 7 数据入口）
        assertThat(evaluator.shouldOcr(region(RegionType.IMAGE, true, 0.9, 0.0, 0.1))).isTrue();
    }

    @Test
    void imageRegion_defaultNotEligible_evenWithLowCoverage() {
        // 核心原则：覆盖率低不构成 OCR 理由
        assertThat(evaluator.shouldOcr(region(RegionType.IMAGE, false, 0.05, 0.1, 0.1))).isFalse();
    }

    @Test
    void imageRegion_eligibleWithStrongTextCandidate() {
        assertThat(evaluator.shouldOcr(region(RegionType.IMAGE, false, 0.05, 0.60, 0.1))).isTrue();
        assertThat(evaluator.shouldOcr(region(RegionType.IMAGE, false, 0.3, 0.85, 0.1))).isTrue();
    }

    @Test
    void unknownRegion_eligibleOnlyWithCandidateAndLowCoverage() {
        assertThat(evaluator.shouldOcr(region(RegionType.UNKNOWN, false, 0.2, 0.8, 0.1))).isTrue();
        // 高覆盖（文字层已覆盖）→ 不 OCR
        assertThat(evaluator.shouldOcr(region(RegionType.UNKNOWN, false, 0.6, 0.8, 0.1))).isFalse();
        // 无视觉文本候选 → 不 OCR
        assertThat(evaluator.shouldOcr(region(RegionType.UNKNOWN, false, 0.2, 0.3, 0.1))).isFalse();
    }

    @Test
    void fullPageRegion_exempt_evenWithStrongCandidate() {
        // 整页扫描底图的区域 OCR = 变相整页 OCR（禁止项）
        assertThat(evaluator.shouldOcr(region(RegionType.IMAGE, false, 0.05, 0.95, 0.99))).isFalse();
        assertThat(evaluator.shouldOcr(region(RegionType.UNKNOWN, false, 0.05, 0.95, 0.85))).isFalse();
    }
}
