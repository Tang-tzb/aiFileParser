package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import com.aifp.aiagent.parser.pdf.text.TextLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CoordinateTableRecognizer} 单元测试（方式二：文字坐标聚类兜底）：
 * X/Y 聚类成格、值直接绑定（PDF_TEXT/1.0）、纵排续行合并（多行值一格）、
 * 有效性门槛（不足 2×2 返回空）与异常封闭。
 *
 * @author Tang_tzb
 */
class CoordinateTableRecognizerTest {

    private final CoordinateTableRecognizer recognizer = new CoordinateTableRecognizer();

    // ---------- 正常聚类 ----------

    @Test
    void twoByTwoGrid_cellsWithValues() {
        TableGrid grid = singleGrid(input(
                block("单位名称", 40, 330, 80, 12),
                block("XX公司", 180, 330, 80, 12),
                block("建筑面积", 40, 290, 80, 12),
                block("1000㎡", 180, 290, 80, 12)));

        assertThat(grid.getRowCount()).isEqualTo(2);
        assertThat(grid.getColumnCount()).isEqualTo(2);
        assertThat(grid.getCells()).hasSize(4);

        TableCell topLeft = cellAt(grid, 0, 0);
        assertThat(topLeft.getValue()).isEqualTo("单位名称");
        assertThat(topLeft.getBoundingBox().getX()).isCloseTo(40f, within(0.01f));
        assertThat(topLeft.getBoundingBox().getY()).isCloseTo(330f, within(0.01f));
        // 值单元格来源约定：PDF_TEXT / confidence=1.0 / 跨度 1
        assertThat(topLeft.getSource()).isEqualTo(ElementSource.PDF_TEXT);
        assertThat(topLeft.getConfidence()).isCloseTo(1.0f, within(0.01f));
        assertThat(topLeft.getRowSpan()).isEqualTo(1);
        assertThat(topLeft.getColSpan()).isEqualTo(1);

        assertThat(cellAt(grid, 1, 1).getValue()).isEqualTo("1000㎡");
    }

    // ---------- 纵排续行合并（多行值 = 一个 Cell） ----------

    @Test
    void verticalContinuation_mergedToOneCell() {
        TableGrid grid = singleGrid(input(
                block("line1", 40, 330, 80, 12),
                block("val", 180, 330, 80, 12),
                block("line2", 40, 306, 80, 12),
                block("val2", 180, 270, 80, 12)));

        // 行带 3 条：[330,342] / [306,318] / [270,282]
        assertThat(grid.getRowCount()).isEqualTo(3);
        assertThat(grid.getColumnCount()).isEqualTo(2);

        // 左列：line1+line2 垂直间隙 12pt < 1.5×行高中位数 → 合并一格
        TableCell merged = cellAt(grid, 0, 0);
        assertThat(merged.getRowSpan()).isEqualTo(2);
        assertThat(merged.getValue()).isEqualTo("line1\nline2");
        assertThat(merged.getBoundingBox().getY()).isCloseTo(306f, within(0.01f));
        assertThat(merged.getBoundingBox().getHeight()).isCloseTo(36f, within(0.01f));

        // 右列：val 与 val2 间隙 48pt ≥ 阈值 → 不合并
        TableCell separate = cellAt(grid, 2, 1);
        assertThat(separate.getRowSpan()).isEqualTo(1);
        assertThat(separate.getValue()).isEqualTo("val2");
    }

    // ---------- 有效性门槛 ----------

    @Test
    void insufficientGrid_returnsEmpty() {
        // 单行 → 行带不足 2
        assertThat(recognizer.recognize(input(
                block("a", 40, 330, 80, 12),
                block("b", 180, 330, 80, 12)))).isEmpty();
        // 单行单列
        assertThat(recognizer.recognize(input(
                block("a", 40, 330, 80, 12)))).isEmpty();
    }

    @Test
    void nullOrEmptyInput_returnsEmpty() {
        assertThat(recognizer.recognize(input())).isEmpty();
        assertThat(recognizer.recognize(null)).isEmpty();
    }

    @Test
    void brokenBlock_exceptionClosed_empty() {
        TextBlock broken = TextBlock.builder().text("x").build();
        assertThat(recognizer.recognize(input(broken))).isEmpty();
    }

    // ---------- 夹具 ----------

    private TableGrid singleGrid(TableRecognitionInput input) {
        List<TableGrid> grids = recognizer.recognize(input);
        assertThat(grids).hasSize(1);
        return grids.get(0);
    }

    private TableRecognitionInput input(TextBlock... blocks) {
        return TableRecognitionInput.builder()
                .pageNumber(1)
                .textBlocks(List.of(blocks))
                .build();
    }

    /**
     * 单行文字块（bbox 即行框；方式二仅消费 block.lines 坐标）。
     */
    private TextBlock block(String text, float x, float y, float w, float h) {
        TextLine line = TextLine.builder().text(text)
                .bbox(BoundingBox.builder().x(x).y(y).width(w).height(h).build())
                .fontSize(12f)
                .fontName("Helvetica")
                .build();
        return TextBlock.builder()
                .text(text)
                .lines(List.of(line))
                .bbox(line.getBbox())
                .fontSize(12f)
                .fontName("Helvetica")
                .page(1)
                .build();
    }

    private TableCell cellAt(TableGrid grid, int row, int col) {
        return grid.getCells().stream()
                .filter(c -> c.getRowIndex() == row && c.getColumnIndex() == col)
                .findFirst()
                .orElseThrow();
    }
}
