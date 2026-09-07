package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.region.RegionType;
import com.aifp.aiagent.parser.pdf.region.VisualRegion;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeaderCandidateDetector} 单元测试（v2 表头候选判定）：
 * 有值 Cell 硬前置排除、位置先验（最左列/最顶行/值左邻）、
 * 视觉特征区间（空白格/图片型数据 Cell 排除）、印章与区域交集排除。
 *
 * @author Tang_tzb
 */
class HeaderCandidateDetectorTest {

    private static final int IMG_W = 300;
    private static final int IMG_H = 400;
    private static final float DPI = 72f;

    private final HeaderCandidateDetector detector = new HeaderCandidateDetector(
            new CoordinateMatcher(new SimpleCoordinateTransformer()));

    // ---------- 硬前置：有值 Cell 永不为候选 ----------

    @Test
    void valueCell_neverCandidate() {
        TableCell cell = cell(0, 0, 40, 250, 100, 100, "数据");

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(cell), baseImage(), DPI, IMG_H, List.of());

        assertThat(candidates).isEmpty();
    }

    // ---------- 位置先验 ----------

    @Test
    void leftColumnTextCell_isCandidate() {
        // 左列空白骨架 cell（bbox → 像素区 x 40..139, y 50..149）
        TableCell cell = cell(1, 0, 40, 250, 100, 100, null);
        BufferedImage image = imageWithBands(50, 40, 70, 95, 120);

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(cell), image, DPI, IMG_H, List.of());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).cell()).isSameAs(cell);
        assertThat(candidates.get(0).reason()).contains("最左列");
    }

    @Test
    void topRowTextCell_isCandidate() {
        // 顶行 cell（row 0）+ 一个值 cell 提供位置参照（minRow=0/minColumn=0）
        TableCell topCell = cell(0, 1, 160, 250, 100, 100, null);
        TableCell valueCell = cell(1, 0, 40, 120, 100, 100, "v");
        BufferedImage image = imageWithBands(170, 40, 70, 95, 120);

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(topCell, valueCell), image, DPI, IMG_H, List.of());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).cell()).isSameAs(topCell);
        assertThat(candidates.get(0).reason()).contains("最顶行");
    }

    @Test
    void valueLeftNeighbor_isCandidate() {
        // c1(2,1) 无值有墨迹；同行的 c0(2,0) 有值 → "值左邻"先验命中
        TableCell c0 = cell(2, 0, 10, 50, 50, 100, "v2");
        TableCell c1 = cell(2, 1, 60, 50, 80, 100, null);
        TableCell c2 = cell(2, 2, 140, 50, 60, 100, "v3");
        TableCell v0 = cell(0, 0, 220, 250, 60, 100, "v");
        BufferedImage image = imageWithBands(70, 40, 270, 295, 320);

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(c0, c1, c2, v0), image, DPI, IMG_H, List.of());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).cell()).isSameAs(c1);
        assertThat(candidates.get(0).reason()).contains("值左邻");
    }

    @Test
    void middleCellWithoutPrior_rejected() {
        // (1,1) 非最左列/最顶行，且左邻 (0,0) 虽同行……不重叠，位置先验全不满足
        TableCell valueCell = cell(0, 0, 220, 250, 60, 100, "v");
        TableCell middleCell = cell(1, 1, 60, 50, 80, 100, null);
        BufferedImage image = imageWithBands(70, 40, 70, 95, 120);

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(valueCell, middleCell), image, DPI, IMG_H, List.of());

        assertThat(candidates).isEmpty();
    }

    // ---------- 视觉特征排除 ----------

    @Test
    void blankCell_rejected() {
        TableCell cell = cell(1, 0, 40, 250, 100, 100, null);

        List<HeaderCandidateDetector.HeaderCandidate> candidates =
                detector.detect(List.of(cell), baseImage(), DPI, IMG_H, List.of());

        assertThat(candidates).isEmpty();
    }

    @Test
    void imageLikeCell_rejected() {
        // 大面积实心图形（inkRatio=1.0 > 上限 0.50）：普通 IMAGE 型数据 Cell 排除
        TableCell cell = cell(1, 0, 40, 250, 100, 100, null);
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(40, 50, 100, 100);
        g.dispose();

        assertThat(detector.detect(List.of(cell), image, DPI, IMG_H, List.of())).isEmpty();
    }

    // ---------- 印章 / 区域排除 ----------

    @Test
    void redStampCell_rejected() {
        TableCell cell = cell(1, 0, 40, 250, 100, 100, null);
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 40, 40));
        g.fillRect(40, 50, 100, 100);
        g.dispose();

        assertThat(detector.detect(List.of(cell), image, DPI, IMG_H, List.of())).isEmpty();
    }

    @Test
    void stampRegionOverlap_rejected() {
        TableCell cell = cell(1, 0, 40, 250, 100, 100, null);
        BoundingBox cellBox = cell.getBoundingBox();
        VisualRegion stamp = VisualRegion.builder()
                .regionType(RegionType.STAMP)
                .bbox(BoundingBox.builder()
                        .x(cellBox.getX()).y(cellBox.getY())
                        .width(cellBox.getWidth()).height(cellBox.getHeight())
                        .build())
                .build();

        assertThat(detector.detect(List.of(cell), baseImage(), DPI, IMG_H,
                List.of(stamp))).isEmpty();
    }

    // ---------- 夹具 ----------

    private TableCell cell(int row, int col, float x, float y, float w, float h, String value) {
        return TableCell.builder()
                .rowIndex(row)
                .columnIndex(col)
                .rowSpan(1)
                .colSpan(1)
                .boundingBox(BoundingBox.builder().x(x).y(y).width(w).height(h).build())
                .value(value)
                .build();
    }

    /**
     * 白底图 + 若干类文字行带（高 10px 的黑色横带，模拟表头文字笔画）。
     */
    private BufferedImage imageWithBands(int bandX, int bandWidth, int... bandTopRows) {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        for (int top : bandTopRows) {
            g.fillRect(bandX, top, bandWidth, 10);
        }
        g.dispose();
        return image;
    }

    private BufferedImage baseImage() {
        BufferedImage image = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, IMG_W, IMG_H);
        g.dispose();
        return image;
    }
}
