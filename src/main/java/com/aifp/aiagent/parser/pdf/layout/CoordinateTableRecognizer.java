package com.aifp.aiagent.parser.pdf.layout;

import com.aifp.aiagent.parser.pdf.page.ElementSource;
import com.aifp.aiagent.parser.pdf.text.BoundingBox;
import com.aifp.aiagent.parser.pdf.text.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 方式二表格识别器：文字坐标 X/Y 聚类兜底（阶段 7，对应《PDF解析改造方案》§九情况二）。
 * <p>
 * 适用于无明显表格线的表格：文字行 Y 聚类（行距）→ x 起点 X 聚类（列距）→
 * 行带 × 列带 = 单元格 → <b>纵排续行合并</b>（同列上下相邻、x 区间高度重叠、
 * 垂直间隙小于行高中位数 × 因子 → 合并为一格，保障"建设地点类多行值 = 一个 Cell"）。
 * <p>
 * 定位：作为 {@link ImageTableRecognizer} 的<b>回退路径</b>（无边框/复杂视觉
 * 表格的兜底），仅依赖 PDF 原生文字行坐标；有效性门槛不满足时返回空。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class CoordinateTableRecognizer implements TableStructureRecognizer {

    /**
     * 行聚类：y 区间重叠占比下限（×较矮者）
     */
    private static final double ROW_OVERLAP_RATIO = 0.50;
    /**
     * 列聚类：x 起点对齐容差（pt）
     */
    private static final float COL_ALIGN_TOLERANCE_PT = 3f;
    /**
     * 续行合并：x 区间重叠占比下限
     */
    private static final double CONTINUE_X_OVERLAP = 0.80;

    /**
     * 续行合并的行距因子（×行高中位数）
     */
    @Value("${document.parser.pdf.table.coord-row-gap-factor:1.5}")
    private double coordRowGapFactor = 1.5;

    /**
     * TextBlock 展开为行级坐标集合（TextBlock.lines，行内文字/bbox 保留）。
     */
    private static List<LineBox> expandLines(List<TextBlock> textBlocks) {
        List<LineBox> lines = new ArrayList<>();
        for (TextBlock block : textBlocks == null ? List.<TextBlock>of() : textBlocks) {
            for (com.aifp.aiagent.parser.pdf.text.TextLine line : block.getLines()) {
                lines.add(new LineBox(line.getText(), line.getBbox()));
            }
        }
        return lines;
    }

    /**
     * 列聚类（列距）：x 起点对齐（容差 3pt）→ 列带；按 repX 升序排序。
     */
    private static List<ColBand> clusterColumns(List<LineBox> lines) {
        List<LineBox> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(l -> l.bbox().getX()));
        List<ColBand> bands = new ArrayList<>();
        for (LineBox line : sorted) {
            ColBand target = null;
            for (ColBand band : bands) {
                if (Math.abs(line.bbox().getX() - band.repX) <= COL_ALIGN_TOLERANCE_PT) {
                    target = band;
                    break;
                }
            }
            if (target == null) {
                target = new ColBand(line.bbox().getX());
                bands.add(target);
            }
            target.members.add(line);
        }
        bands.sort(Comparator.comparingDouble(b -> b.repX));
        return bands;
    }

    // ---------- 输入展开 ----------

    /**
     * 行带 × 列带 → 原始单元格（有文字行处才有单元格），按列分组返回。
     * 列归属用聚类同款规则确定性重放（x 起点对齐容差），避免成员身份匹配歧义。
     */
    private static Map<Integer, List<MutableCell>> buildRawCells(List<RowBand> rowBands,
                                                                 List<ColBand> colBands) {
        Map<Integer, List<MutableCell>> byColumn = new LinkedHashMap<>();
        for (int r = 0; r < rowBands.size(); r++) {
            for (LineBox line : rowBands.get(r).members) {
                int c = columnIndex(colBands, line.bbox().getX());
                if (c < 0) {
                    continue;
                }
                MutableCell cell = new MutableCell(r, c, line.bbox(), line.text());
                byColumn.computeIfAbsent(c, key -> new ArrayList<>()).add(cell);
            }
        }
        return byColumn;
    }

    // ---------- 行/列聚类 ----------

    /**
     * 定位 x 起点所属列带索引（与聚类同款规则；容差内取最近者）。
     */
    private static int columnIndex(List<ColBand> colBands, float xStart) {
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < colBands.size(); c++) {
            float dist = Math.abs(xStart - colBands.get(c).repX);
            if (dist <= COL_ALIGN_TOLERANCE_PT && dist < bestDist) {
                best = c;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * 行高中位数（pt，续行合并的间距基准）。
     */
    private static double medianLineHeight(List<LineBox> lines) {
        List<Float> heights = lines.stream()
                .map(l -> l.bbox().getHeight())
                .sorted()
                .toList();
        return heights.isEmpty() ? 0 : heights.get(heights.size() / 2);
    }

    // ---------- 单元格与续行合并 ----------

    /**
     * 组装格网：grid bbox = 单元格并集；行 bbox = 行带 y 区间 × 格网 x 区间。
     */
    private static TableGrid assembleGrid(List<RowBand> rowBands, int columnCount,
                                          List<TableCell> cells, int pageNumber) {
        BoundingBox gridBbox = null;
        for (TableCell cell : cells) {
            gridBbox = gridBbox == null ? cell.getBoundingBox() : gridBbox.union(cell.getBoundingBox());
        }
        List<TableRow> rows = new ArrayList<>(rowBands.size());
        for (int r = 0; r < rowBands.size(); r++) {
            RowBand band = rowBands.get(r);
            final int rowIndex = r;
            BoundingBox rowBbox = BoundingBox.builder()
                    .x(gridBbox == null ? 0 : gridBbox.getX())
                    .y(band.bottom)
                    .width(gridBbox == null ? 0 : gridBbox.getWidth())
                    .height(band.top - band.bottom)
                    .build();
            List<TableCell> rowCells = cells.stream()
                    .filter(c -> c.getRowIndex() == rowIndex)
                    .toList();
            rows.add(TableRow.builder().index(rowIndex).bbox(rowBbox).cells(rowCells).build());
        }
        return TableGrid.builder()
                .pageNumber(pageNumber)
                .bbox(gridBbox)
                .rowCount(rowBands.size())
                .columnCount(columnCount)
                .rows(rows)
                .cells(cells)
                .build();
    }

    @Override
    public List<TableGrid> recognize(TableRecognitionInput input) {
        try {
            TableGrid grid = doRecognize(input);
            return grid == null ? List.of() : List.of(grid);
        } catch (Exception e) {
            // 全异常封闭：识别失败降级为空
            log.warn("坐标聚类表格识别失败降级 pageNumber={}",
                    input == null ? -1 : input.getPageNumber(), e);
            return List.of();
        }
    }

    /**
     * 主流程：展开行 → 行/列聚类 → 有效性门槛 → 单元格 → 续行合并 → 组装。
     */
    private TableGrid doRecognize(TableRecognitionInput input) {
        List<LineBox> lines = expandLines(input.getTextBlocks());
        if (lines.isEmpty()) {
            return null;
        }
        List<RowBand> rowBands = clusterRows(lines);
        List<ColBand> colBands = clusterColumns(lines);
        if (rowBands.size() < 2 || colBands.size() < 2) {
            log.debug("坐标聚类格网不足（需至少2行2列） pageNumber={}, rows={}, cols={}",
                    input.getPageNumber(), rowBands.size(), colBands.size());
            return null;
        }
        Map<Integer, List<MutableCell>> cellsByColumn = buildRawCells(rowBands, colBands);
        double medianLineHeight = medianLineHeight(lines);
        cellsByColumn.values().forEach(col -> mergeContinuations(col, medianLineHeight));
        List<TableCell> cells = cellsByColumn.values().stream()
                .flatMap(List::stream)
                .map(MutableCell::toCell)
                .sorted(Comparator.comparingInt(TableCell::getRowIndex)
                        .thenComparingInt(TableCell::getColumnIndex))
                .toList();
        TableGrid grid = assembleGrid(rowBands, colBands.size(), cells, input.getPageNumber());
        log.info("坐标聚类表格识别完成 pageNumber={}, grid={}x{}, cells={}",
                input.getPageNumber(), grid.getRowCount(), grid.getColumnCount(), cells.size());
        return grid;
    }

    /**
     * 行聚类（行距）：行 y 区间重叠 ≥50%×较矮者 → 同一带；按 y 顶→底排序。
     */
    private List<RowBand> clusterRows(List<LineBox> lines) {
        List<LineBox> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble((LineBox l) -> l.bbox().top()).reversed());
        List<RowBand> bands = new ArrayList<>();
        for (LineBox line : sorted) {
            RowBand target = null;
            for (RowBand band : bands) {
                if (band.overlaps(line.bbox())) {
                    target = band;
                    break;
                }
            }
            if (target == null) {
                target = new RowBand(line.bbox());
                bands.add(target);
            }
            target.add(line.bbox());
            target.members.add(line);
        }
        bands.sort(Comparator.comparingDouble((RowBand b) -> b.top).reversed());
        return bands;
    }

    /**
     * 纵排续行合并：同列上下相邻单元格，x 区间重叠 ≥80% 且垂直间隙
     * &lt; 因子×行高中位数 → 合并为一格（rowSpan 累积，多行值并入同一 Cell）。
     */
    private void mergeContinuations(List<MutableCell> columnCells, double medianLineHeight) {
        columnCells.sort(Comparator.comparingInt(MutableCell::getRow));
        int i = 0;
        while (i + 1 < columnCells.size()) {
            MutableCell above = columnCells.get(i);
            MutableCell below = columnCells.get(i + 1);
            if (canMerge(above, below, medianLineHeight)) {
                above.absorb(below);
                columnCells.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    // ---------- 组装 ----------

    /**
     * 合并条件：x 区间重叠占比 ≥80%（×较窄者）且垂直间隙 &lt; 因子×行高中位数。
     */
    private boolean canMerge(MutableCell above, MutableCell below, double medianLineHeight) {
        BoundingBox a = above.bbox;
        BoundingBox b = below.bbox;
        float overlapRight = Math.min(a.right(), b.right());
        float overlapLeft = Math.max(a.getX(), b.getX());
        double minwidth = Math.min(a.getWidth(), b.getWidth());
        double xOverlap = minwidth <= 0 ? 0 : (overlapRight - overlapLeft) / minwidth;
        float verticalGap = a.getY() - b.top();
        return xOverlap >= CONTINUE_X_OVERLAP
                && verticalGap < coordRowGapFactor * medianLineHeight;
    }

    // ---------- 内部模型 ----------

    /**
     * 行级坐标盒（文字 + PDF 用户空间 bbox）。
     */
    private record LineBox(String text, BoundingBox bbox) {
    }

    /**
     * 行带：成员行的 y 区间并集（top = 最大 y，bottom = 最小 y，PDF 用户空间）。
     */
    private static final class RowBand {
        private final List<LineBox> members = new ArrayList<>();
        private float top;
        private float bottom;

        private RowBand(BoundingBox initial) {
            top = initial.top();
            bottom = initial.getY();
        }

        private void add(BoundingBox bbox) {
            top = Math.max(top, bbox.top());
            bottom = Math.min(bottom, bbox.getY());
        }

        /**
         * y 区间重叠 ≥50%×较矮者（行距聚类判据）。
         */
        private boolean overlaps(BoundingBox bbox) {
            float overlap = Math.min(top, bbox.top()) - Math.max(bottom, bbox.getY());
            if (overlap <= 0) {
                return false;
            }
            double minHeight = Math.min(top - bottom, bbox.getHeight());
            return minHeight > 0 && overlap / minHeight >= ROW_OVERLAP_RATIO;
        }
    }

    /**
     * 列带：x 起点对齐的成员行集合（repX = 首个成员的 x 起点）。
     */
    private static final class ColBand {
        private final float repX;
        private final List<LineBox> members = new ArrayList<>();

        private ColBand(float repX) {
            this.repX = repX;
        }
    }

    /**
     * 可变单元格构建器：吸收成员行文字与续行合并（rowSpan 累积）。
     */
    private static final class MutableCell {
        private final int row;
        private final int column;
        private final List<String> texts = new ArrayList<>();
        private BoundingBox bbox;
        private int rowSpan = 1;

        private MutableCell(int row, int column, BoundingBox initial, String text) {
            this.row = row;
            this.column = column;
            this.bbox = initial;
            this.texts.add(text);
        }

        private int getRow() {
            return row;
        }

        private void absorb(MutableCell other) {
            rowSpan = (other.row + other.rowSpan) - row;
            bbox = bbox.union(other.bbox);
            texts.addAll(other.texts);
        }

        /**
         * 值 = 成员行按阅读序（y 顶→底）以 \n 连接；source=PDF_TEXT、confidence=1.0。
         */
        private TableCell toCell() {
            return TableCell.builder()
                    .rowIndex(row)
                    .columnIndex(column)
                    .rowSpan(rowSpan)
                    .colSpan(1)
                    .boundingBox(bbox)
                    .value(String.join("\n", texts))
                    .source(ElementSource.PDF_TEXT)
                    .confidence(1.0f)
                    .build();
        }
    }
}
