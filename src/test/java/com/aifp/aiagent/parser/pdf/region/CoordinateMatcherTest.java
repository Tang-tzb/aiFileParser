package com.aifp.aiagent.parser.pdf.region;

import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CoordinateMatcher} 测试：主去重判定（intersectionArea/ocrWordArea，
 * 非 IoU）、辅助指标、空间合法性与像素裁剪换算。
 *
 * @author Tang_tzb
 */
class CoordinateMatcherTest {

    private static final float PAGE_H = 842f;

    private final CoordinateMatcher matcher =
            new CoordinateMatcher(new SimpleCoordinateTransformer());

    private BoundingBox box(float x, float y, float w, float h) {
        return BoundingBox.builder().x(x).y(y).width(w).height(h).build();
    }

    private TextBlock block(BoundingBox bbox) {
        return TextBlock.builder().text("t").bbox(bbox).fontSize(12f).fontName("H").page(1).build();
    }

    // ---------- coverageRatio（区域文字覆盖率） ----------

    @Test
    void coverageRatio_singleBlockFraction() {
        // 区域 100x100，块 50x50 完全在内 → 25%
        double ratio = matcher.coverageRatio(box(0, 0, 100, 100),
                List.of(block(box(10, 10, 50, 50))));
        assertThat(ratio).isCloseTo(0.25, within(1e-6));
    }

    @Test
    void coverageRatio_unionCapsOverlappingBlocks() {
        // 两块互相重叠覆盖同一区域 → 增量合并为外接矩形 (10,10,60x60)=3600，
        // 3600/10000=0.36 < 0.25+0.25（重叠部分不重复计面积）
        double ratio = matcher.coverageRatio(box(0, 0, 100, 100),
                List.of(block(box(10, 10, 50, 50)), block(box(20, 20, 50, 50))));
        assertThat(ratio).isCloseTo(0.36, within(1e-6));
    }

    @Test
    void coverageRatio_outsideBlockContributesNothing() {
        double ratio = matcher.coverageRatio(box(0, 0, 100, 100),
                List.of(block(box(200, 200, 50, 50))));
        assertThat(ratio).isCloseTo(0.0, within(1e-6));
    }

    // ---------- wordCoverageRatio（主去重判定） ----------

    @Test
    void wordCoverage_fullyCoveredByOneBlock() {
        double ratio = matcher.wordCoverageRatio(box(20, 20, 10, 10),
                List.of(block(box(0, 0, 100, 100))));
        assertThat(ratio).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void wordCoverage_partialCover() {
        // 词 10x10，一半被覆盖 → 0.5
        double ratio = matcher.wordCoverageRatio(box(95, 20, 10, 10),
                List.of(block(box(0, 0, 100, 100))));
        assertThat(ratio).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void wordCoverage_unionAcrossTwoAdjacentBlocks() {
        // 词跨两块相邻文字块：左 5pt + 右 5pt 联合覆盖 → 1.0
        // （中文场景一个 TextBlock 常覆盖多个 OCR 词，IoU 会系统性偏低，
        //   故主判定为词面积被覆盖比例）
        double ratio = matcher.wordCoverageRatio(box(45, 20, 10, 10),
                List.of(block(box(0, 0, 50, 100)), block(box(50, 0, 50, 100))));
        assertThat(ratio).isCloseTo(1.0, within(1e-6));
    }

    // ---------- isDuplicateWord ----------

    @Test
    void duplicate_byCoverageRatio() {
        boolean duplicate = matcher.isDuplicateWord(box(20, 20, 10, 10),
                List.of(block(box(0, 0, 100, 100))));
        assertThat(duplicate).isTrue();
    }

    @Test
    void duplicate_byCenterPointFallback() {
        // 覆盖率 0.64（低于 0.80）、IoU 0.47，但词中心点落在文字块内 → 辅助命中判重
        boolean duplicate = matcher.isDuplicateWord(box(92, 92, 10, 10),
                List.of(block(box(0, 0, 100, 100))));
        assertThat(duplicate).isTrue();
    }

    @Test
    void notDuplicate_whenAllMetricsMiss() {
        boolean duplicate = matcher.isDuplicateWord(box(300, 300, 10, 10),
                List.of(block(box(0, 0, 100, 100))));
        assertThat(duplicate).isFalse();
    }

    // ---------- isWithinRegion（空间合法性，容差语义） ----------

    @Test
    void withinRegion_inside() {
        assertThat(matcher.isWithinRegion(box(120, 420, 50, 20), box(100, 400, 200, 150))).isTrue();
    }

    @Test
    void withinRegion_outsideButWithinTolerance() {
        // 元素紧贴区域边界外 1pt（< 容差 2pt），外扩后有有效交集 → 合法
        assertThat(matcher.isWithinRegion(box(301, 400, 5, 5), box(100, 400, 200, 150))).isTrue();
    }

    @Test
    void withinRegion_farOutside() {
        assertThat(matcher.isWithinRegion(box(400, 700, 50, 20), box(100, 400, 200, 150))).isFalse();
    }

    // ---------- pixelCropBounds（像素裁剪换算） ----------

    @Test
    void pixelCropBounds_exactConversion() {
        // 72 DPI（k=1）：PDF (100, 400, 200x150) → 像素 x=100, y=842-550=292
        int[] crop = matcher.pixelCropBounds(box(100, 400, 200, 150), 72f, PAGE_H, 595, 842);
        assertThat(crop).containsExactly(100, 292, 200, 150);
    }

    @Test
    void pixelCropBounds_clampedToImageBounds() {
        // 区域越出页面边界 → 钳制到图像尺寸内
        int[] crop = matcher.pixelCropBounds(box(500, 800, 200, 100), 72f, PAGE_H, 595, 842);
        assertThat(crop[0]).isGreaterThanOrEqualTo(0);
        assertThat(crop[1]).isGreaterThanOrEqualTo(0);
        assertThat(crop[0] + crop[2]).isLessThanOrEqualTo(595);
        assertThat(crop[1] + crop[3]).isLessThanOrEqualTo(842);
    }

    @Test
    void pixelCropBounds_minSizeOnePixel() {
        int[] crop = matcher.pixelCropBounds(box(100, 400, 0.001f, 0.001f), 72f, PAGE_H, 595, 842);
        assertThat(crop[2]).isGreaterThanOrEqualTo(1);
        assertThat(crop[3]).isGreaterThanOrEqualTo(1);
    }
}
