package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.region.CoordinateMatcher;
import com.aifp.aiagent.parser.pdf.text.SimpleCoordinateTransformer;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link ImageTableRecognizer} 单元测试（方式一：规则型表格线恢复器）：
 * 合成格网检测、rowSpan/colSpan 公共边合并、印章红段投票排除、
 * 文字噪声不致伪格网、异常封闭与 PDF 用户空间坐标翻转。
 *
 * @author Tang_tzb
 */
class ImageTableRecognizerTest {

    private static final int IMG_W = 300;
    private static final int IMG_H = 400;
    private static final float DPI = 72f;
    private static final int[] XS = {50, 150, 250};
    private static final int[] YS = {50, 150, 250, 350};
    private static final int WHITE = 0xFFFFFF;

    private final ImageTableRecognizer recognizer = new ImageTableRecognizer(
            new CoordinateMatcher(new SimpleCoordinateTransformer()));

    // ---------- 正常格网 ----------

    private static void eraseRowSegment(BufferedImage image, int row, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            image.setRGB(x, row, WHITE);
        }
    }

    // ---------- 跨行/跨列合并（公共边缺失 → 并查集合并） ----------

    private static void eraseColumnSegment(BufferedImage image, int col, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            image.setRGB(col, y, WHITE);
        }
    }

    @Test
    void borderedGrid_detected_3x2_pdfSpaceFlipped() {
        TableGrid grid = singleGrid(gridImage());

        assertThat(grid.getRowCount()).isEqualTo(3);
        assertThat(grid.getColumnCount()).isEqualTo(2);
        assertThat(grid.getCells()).hasSize(6);
        assertThat(grid.getCells()).allSatisfy(cell -> {
            assertThat(cell.getRowSpan()).isEqualTo(1);
            assertThat(cell.getColSpan()).isEqualTo(1);
            assertThat(cell.getBoundingBox()).isNotNull();
        });
        // 像素 → PDF 用户空间（Y 轴翻转）：图像顶行 = PDF y 最大
        TableCell topLeft = cellAt(grid, 0, 0);
        assertThat(topLeft.getBoundingBox().getX()).isCloseTo(50f, within(0.6f));
        assertThat(topLeft.getBoundingBox().getY()).isCloseTo(250f, within(0.6f));
        assertThat(topLeft.getBoundingBox().getWidth()).isCloseTo(100f, within(0.6f));
        assertThat(topLeft.getBoundingBox().getHeight()).isCloseTo(100f, within(0.6f));
        assertThat(cellAt(grid, 2, 1).getBoundingBox().getY()).isCloseTo(50f, within(0.6f));
        // grid bbox = 全部单元格并集
        assertThat(grid.getBbox().getX()).isCloseTo(50f, within(0.6f));
        assertThat(grid.getBbox().getY()).isCloseTo(50f, within(0.6f));
        assertThat(grid.getBbox().getWidth()).isCloseTo(200f, within(0.6f));
        assertThat(grid.getBbox().getHeight()).isCloseTo(300f, within(0.6f));
    }

    // ---------- 多段采样 + 多数投票：印章不致误合并 ----------

    @Test
    void missingHorizontalEdge_rowSpan2() {
        BufferedImage image = gridImage();
        // 抹掉横线 y=150 在左列（x 48..151）的区段：左列上下两格公共边缺失
        eraseRowSegment(image, 150, 48, 151);

        TableGrid grid = singleGrid(image);

        TableCell merged = cellAt(grid, 0, 0);
        assertThat(merged.getRowSpan()).isEqualTo(2);
        assertThat(merged.getColSpan()).isEqualTo(1);
        // bbox = 两行像素框并集：y_px 50..250 → PDF y 150..350
        assertThat(merged.getBoundingBox().getY()).isCloseTo(150f, within(0.6f));
        assertThat(merged.getBoundingBox().getHeight()).isCloseTo(200f, within(0.6f));
        // 右列公共边仍在 → 不合并
        assertThat(cellAt(grid, 0, 1).getRowSpan()).isEqualTo(1);
        assertThat(grid.getCells()).hasSize(5);
    }

    // ---------- 门槛与噪声 ----------

    @Test
    void missingVerticalEdge_colSpan2() {
        BufferedImage image = gridImage();
        // 抹掉竖线 x=150 在首行（y 48..151）的区段：首行左右两格公共边缺失
        eraseColumnSegment(image, 150, 48, 151);

        TableGrid grid = singleGrid(image);

        TableCell merged = cellAt(grid, 0, 0);
        assertThat(merged.getColSpan()).isEqualTo(2);
        assertThat(merged.getRowSpan()).isEqualTo(1);
        assertThat(merged.getBoundingBox().getX()).isCloseTo(50f, within(0.6f));
        assertThat(merged.getBoundingBox().getWidth()).isCloseTo(200f, within(0.6f));
        assertThat(grid.getCells()).hasSize(5);
    }

    @Test
    void stampRedSegments_excludedFromVote_edgeStillHolds() {
        BufferedImage image = gridImage();
        // 红色矩形覆盖横线 y=150 左侧约 3 个投票段（红像素段不计入投票，
        // 其余暗段仍通过多数投票 → 公共边成立 → 不发生误合并）
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 40, 40));
        g.fillRect(50, 148, 41, 6);
        g.dispose();

        TableGrid grid = singleGrid(image);

        assertThat(grid.getCells()).hasSize(6);
        assertThat(grid.getCells()).allSatisfy(cell -> {
            assertThat(cell.getRowSpan()).isEqualTo(1);
            assertThat(cell.getColSpan()).isEqualTo(1);
        });
    }

    @Test
    void textNoise_noFalseGrid() {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        // 文字级笔画：行程远小于横线门槛（0.15×300=45px）
        g.fillRect(60, 60, 30, 8);
        g.fillRect(120, 60, 30, 8);
        g.fillRect(60, 120, 30, 8);
        g.dispose();

        assertThat(recognizer.recognize(input(image))).isEmpty();
    }

    // ---------- 夹具 ----------

    @Test
    void nullOrTinyImage_empty() {
        assertThat(recognizer.recognize(input(null))).isEmpty();
        assertThat(recognizer.recognize(input(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)))).isEmpty();
    }

    @Test
    void nullInput_exceptionClosed_empty() {
        assertThat(recognizer.recognize(null)).isEmpty();
    }

    private TableGrid singleGrid(BufferedImage image) {
        List<TableGrid> grids = recognizer.recognize(input(image));
        assertThat(grids).hasSize(1);
        return grids.get(0);
    }

    private TableRecognitionInput input(BufferedImage image) {
        return TableRecognitionInput.builder()
                .pageNumber(1)
                .renderedPage(image)
                .dpi(DPI)
                .pageWidth(IMG_W)
                .pageHeight(IMG_H)
                .textBlocks(List.of())
                .regions(List.of())
                .build();
    }

    private TableCell cellAt(TableGrid grid, int row, int col) {
        return grid.getCells().stream()
                .filter(c -> c.getRowIndex() == row && c.getColumnIndex() == col)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 白底 + 1px 黑色表格线：竖线 x∈XS、横线 y∈YS（72dpi 下像素 = pt）。
     */
    private BufferedImage gridImage() {
        BufferedImage image = baseImage();
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        for (int x : XS) {
            g.drawLine(x, YS[0], x, YS[YS.length - 1]);
        }
        for (int y : YS) {
            g.drawLine(XS[0], y, XS[XS.length - 1], y);
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
